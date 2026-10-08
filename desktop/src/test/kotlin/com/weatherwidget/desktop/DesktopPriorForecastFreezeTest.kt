package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop end to end for the triple bar's left slot: the v26 `priorForecast*` columns survive a DAO
 * round-trip and an upgrade from v25, and [DesktopWeatherRepository.settlePastForecastOverlays]
 * freezes "yesterday's forecast" (06:00 / 16:00 anchors) without dropping the settled overlay it
 * writes in the same full-row REPLACE. See plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md.
 */
@Category(ShortDuration::class)
class DesktopPriorForecastFreezeTest {
    private lateinit var path: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.4166
    private val lon = -122.0889
    private val zone = ZoneId.systemDefault()
    private val yesterday = LocalDate.now(zone).minusDays(1)
    private val dateMs = yesterday.toEpochDay() * 86_400_000L

    /** Epoch ms on [date] at [hour]:[minute] local. */
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        path = Files.createTempFile("prior", ".db")
        db = DesktopWeatherDatabase(path).apply { initialize() }
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    private fun exec(sql: String) = db.getConnection().use { c -> c.createStatement().use { it.execute(sql) } }

    private fun insertFetch(fetchedAt: Long, high: Float, low: Float) = exec(
        "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, " +
            "condition, isClimateNormal, source, batchFetchedAt, fetchedAt) VALUES " +
            "($dateMs, $dateMs, $lat, $lon, $high, $low, 'Clear', 0, 'OPEN_METEO', $fetchedAt, $fetchedAt)",
    )

    private fun pastRow() = DailyHistory(
        date = dateMs, source = "OPEN_METEO", locationLat = lat, locationLon = lon,
        computedHighTemp = 91.6f, computedLowTemp = 59.0f, condition = "Clear", updatedAt = 1L,
        forecastHighTemp = 89f, forecastLowTemp = 58f,
        computedHighAt = at(yesterday, 16, 15), computedLowAt = at(yesterday, 5, 15),
    )

    @Test
    fun `prior forecast round-trips through the DAO`() {
        dao.upsertDailyHistory(listOf(pastRow().copy(priorForecastHighTemp = 92f, priorForecastLowTemp = 56f)))
        val stored = dao.getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(92f, stored.priorForecastHighTemp)
        assertEquals(56f, stored.priorForecastLowTemp)
    }

    @Test
    fun `repository freezes the prior forecast and the settled overlay together`() {
        val dayBefore = yesterday.minusDays(1)
        insertFetch(at(dayBefore, 5, 30), 88f, 56f) // low anchor
        insertFetch(at(dayBefore, 15, 30), 92f, 57f) // high anchor
        insertFetch(at(dayBefore, 20), 95f, 61f)
        insertFetch(at(yesterday, 4, 48), 90.0f, 58.3f) // last before the low (05:15)
        insertFetch(at(yesterday, 15, 47), 88.9f, 58.1f) // last before the high (16:15)
        insertFetch(at(yesterday, 22, 25), 89.0f, 58.0f) // hindcast
        dao.upsertDailyHistory(listOf(pastRow()))
        val repository = DesktopWeatherRepository(DesktopWeatherService(lat, lon, "OPEN_METEO"), dao, lat, lon, "OPEN_METEO")

        repository.settlePastForecastOverlays(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(92f, stored.priorForecastHighTemp)
        assertEquals(56f, stored.priorForecastLowTemp)
        assertEquals("the settle's columns survive the same REPLACE", 88.9f, stored.forecastHighTemp)
        assertEquals(58.3f, stored.forecastLowTemp)
        assertTrue(dao.getRecentLogsByTags(listOf("PRIOR_FORECAST_FREEZE"), limit = 5).isNotEmpty())
    }

    @Test
    fun `a v25 database gains the columns on upgrade and keeps its rows`() {
        exec("PRAGMA user_version = 25")
        exec("ALTER TABLE daily_history DROP COLUMN priorForecastHighTemp")
        exec("ALTER TABLE daily_history DROP COLUMN priorForecastLowTemp")
        exec(
            "INSERT INTO daily_history (date, source, locationLat, locationLon, computedHighTemp, computedLowTemp, " +
                "condition, updatedAt, computedHighAt) VALUES ($dateMs, 'NWS', $lat, $lon, 80, 60, 'Clear', 1, 12345)",
        )

        DesktopWeatherDatabase(path).initialize()

        val stored = dao.getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(80f, stored.computedHighTemp)
        assertEquals(12345L, stored.computedHighAt)
        assertNull(stored.priorForecastHighTemp)
        val version = db.getConnection().use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }
        assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, version)
    }

    @Test
    fun `a v26 database gains the forecasts hindcast columns on upgrade and keeps its rows`() {
        exec("PRAGMA user_version = 26")
        exec("ALTER TABLE forecasts DROP COLUMN hindcastHighTemp")
        exec("ALTER TABLE forecasts DROP COLUMN hindcastLowTemp")
        exec(
            "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                "source, batchFetchedAt, fetchedAt) VALUES ($dateMs, $dateMs, $lat, $lon, 80, 60, 'Clear', 'NWS', 1, 1)",
        )

        DesktopWeatherDatabase(path).initialize()

        db.getConnection().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT highTemp, hindcastLowTemp FROM forecasts").use {
                    assertTrue(it.next())
                    assertEquals(80f, it.getFloat("highTemp"))
                    assertNull(it.getObject("hindcastLowTemp"))
                }
                st.executeQuery("PRAGMA user_version").use { it.next(); assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, it.getInt(1)) }
            }
        }
    }
}
