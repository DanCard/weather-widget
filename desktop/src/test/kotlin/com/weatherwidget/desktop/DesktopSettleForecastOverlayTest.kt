package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop end to end: [DesktopWeatherRepository.settlePastForecastOverlays] over a real SQLite file
 * settles a past day's overlay to the last fetches before its extremes, and the v25 columns survive a
 * DAO round-trip and an upgrade from v24. See plans/261004-forecast-overlay-frozen-at-extreme-time.md.
 */
@Category(ShortDuration::class)
class DesktopSettleForecastOverlayTest {
    private lateinit var path: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.4166
    private val lon = -122.0889
    private val zone = ZoneId.systemDefault()
    private val yesterday = LocalDate.now(zone).minusDays(1)
    private val dateMs = yesterday.toEpochDay() * 86_400_000L

    private fun at(hour: Int, minute: Int = 0) = yesterday.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        path = Files.createTempFile("settle", ".db")
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
        computedHighAt = at(16, 15), computedLowAt = at(5, 15),
    )

    @Test
    fun `extreme times round-trip through the DAO`() {
        dao.upsertDailyHistory(listOf(pastRow()))
        val stored = dao.getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(at(16, 15), stored.computedHighAt)
        assertEquals(at(5, 15), stored.computedLowAt)
    }

    @Test
    fun `repository settles a past day to the fetches before its extremes`() {
        insertFetch(at(4, 48), 90.0f, 58.3f)
        insertFetch(at(15, 47), 88.9f, 58.1f)
        insertFetch(at(22, 25), 89.0f, 58.0f)
        dao.upsertDailyHistory(listOf(pastRow()))
        val repository = DesktopWeatherRepository(DesktopWeatherService(lat, lon, "OPEN_METEO"), dao, lat, lon, "OPEN_METEO")

        repository.settlePastForecastOverlays(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(88.9f, stored.forecastHighTemp)
        assertEquals(58.3f, stored.forecastLowTemp)
        assertTrue(dao.getRecentLogsByTags(listOf("FORECAST_OVERLAY_SETTLED"), limit = 5).isNotEmpty())
    }

    @Test
    fun `a v24 database gains the columns on upgrade and keeps its rows`() {
        exec("PRAGMA user_version = 24")
        exec("ALTER TABLE daily_history DROP COLUMN computedHighAt")
        exec("ALTER TABLE daily_history DROP COLUMN computedLowAt")
        exec(
            "INSERT INTO daily_history (date, source, locationLat, locationLon, computedHighTemp, computedLowTemp, " +
                "condition, updatedAt) VALUES ($dateMs, 'NWS', $lat, $lon, 80, 60, 'Clear', 1)",
        )

        DesktopWeatherDatabase(path).initialize()

        val stored = dao.getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(80f, stored.computedHighTemp)
        assertEquals(null, stored.computedHighAt)
        val version = db.getConnection().use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }
        assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, version)
    }
}
