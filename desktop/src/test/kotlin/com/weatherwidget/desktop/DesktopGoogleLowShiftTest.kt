package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Google's daily low filed under the morning it ends, on desktop: the v32 one-time repair of stored
 * rows (real SQLite, upgraded from v31), and today's low kept when a fetch sends none
 * ([DesktopWeatherDao.upsertForecasts]). plans/261010-google-daily-low-filed-under-the-morning-it-ends.md
 */
@Category(ShortDuration::class)
class DesktopGoogleLowShiftTest {
    private val dbPath = Files.createTempFile("weather-google-low-shift-test", ".db")
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val lat = LocationMatch.quantize(37.4170)
    private val lon = LocationMatch.quantize(-122.0890)
    private val day = 86_400_000L
    private val d10 = LocalDate.of(2026, 10, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @After
    fun teardown() {
        Files.deleteIfExists(dbPath)
    }

    private fun sql(block: (java.sql.Statement) -> Unit) =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { c -> c.createStatement().use(block) }

    private fun insert(source: String, targetDate: Long, low: Float?, batch: Long, hindcastLow: Float? = null) = sql {
        it.execute(
            "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                "isClimateNormal, source, batchFetchedAt, fetchedAt, hindcastLowTemp) VALUES " +
                "($targetDate, $d10, $lat, $lon, 70.0, ${low ?: "NULL"}, 'Clear', 0, '$source', $batch, $batch, ${hindcastLow ?: "NULL"})",
        )
    }

    private fun lows(source: String, batch: Long): List<Float?> {
        val out = mutableListOf<Float?>()
        sql { st ->
            st.executeQuery("SELECT lowTemp FROM forecasts WHERE source = '$source' AND batchFetchedAt = $batch ORDER BY targetDate").use { rs ->
                while (rs.next()) out += rs.getFloat(1).takeUnless { rs.wasNull() }
            }
        }
        return out
    }

    private fun upgradeFrom31() {
        sql { it.execute("PRAGMA user_version = 31") }
        DesktopWeatherDatabase(dbPath).initialize()
    }

    @Test
    fun `upgrading shifts each batch's Google lows one day later, batches and sources independent, once`() {
        DesktopWeatherDatabase(dbPath).initialize()
        val b1 = 1_000L
        val b2 = 2_000L
        listOf(49f, 50f, 48f).forEachIndexed { i, low -> insert(google, d10 + i * day, low, b1, hindcastLow = if (i == 0) 49.5f else null) }
        listOf(51f, 47f).forEachIndexed { i, low -> insert(google, d10 + (i + 1) * day, low, b2) }
        listOf(55f, 54f).forEachIndexed { i, low -> insert(WeatherSource.OPEN_METEO.id, d10 + i * day, low, b1) }

        upgradeFrom31()

        assertEquals(listOf(null, 49f, 50f), lows(google, b1))
        assertEquals("a batch's first day has no D−1 in it", listOf(null, 51f), lows(google, b2))
        assertEquals("other sources untouched", listOf(55f, 54f), lows(WeatherSource.OPEN_METEO.id, b1))
        sql { st ->
            st.executeQuery("SELECT hindcastLowTemp FROM forecasts WHERE source = '$google' AND batchFetchedAt = $b1 AND targetDate = ${d10 + day}").use { rs ->
                rs.next(); assertEquals(49.5f, rs.getFloat(1))
            }
        }

        DesktopWeatherDatabase(dbPath).initialize()
        assertEquals("v32 already: runs once", listOf(null, 49f, 50f), lows(google, b1))
    }

    @Test
    fun `a fetch with no low for today keeps the stored one, and its own high`() {
        val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
        val dao = DesktopWeatherDao(database)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        // Yesterday evening's fetch: today 67 / 58.2 (58.2 = Google's yesterday min, this morning).
        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(today.toString(), 67f, 58.2f, "Clear")), nowMs = now - 10 * 3_600_000L)
        // Now: today's low is not in the response.
        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(today.toString(), 68f, null, "Clear")), nowMs = now)

        val row = dao.getDailyForecasts(37.4170, -122.0890, google).single { it.date == today.toString() }
        assertEquals(58.2f, row.lowTemp)
        if (java.time.LocalTime.now(zone).isBefore(com.weatherwidget.shared.util.SameDayExtremeCutoff.HIGH_CUTOFF)) {
            assertEquals("this fetch's high, not the older row's", 68f, row.highTemp)
        }
    }

    @Test
    fun `a fetch with no low for a future day keeps none`() {
        val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
        val dao = DesktopWeatherDao(database)
        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1).toString()
        val now = System.currentTimeMillis()
        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(tomorrow, 67f, 50f, "Clear")), nowMs = now - 3_600_000L)
        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(tomorrow, 68f, null, "Clear")), nowMs = now)

        assertNull(dao.getDailyForecasts(37.4170, -122.0890, google).single { it.date == tomorrow }.lowTemp)
    }

    /** Pixel, 2026-10-10 11:36: the latest stored row for today had no low after the repair. */
    @Test
    fun `today's low is kept from the newest row that has one, past a newer row without`() {
        val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
        val dao = DesktopWeatherDao(database)
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val now = System.currentTimeMillis()
        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(today, 67f, 58.2f, "Clear")), nowMs = now - 10 * 3_600_000L)
        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(today, 68f, null, "Clear")), nowMs = now - 3_600_000L)
        sql { it.execute("UPDATE forecasts SET lowTemp = NULL WHERE fetchedAt = ${now - 3_600_000L}") }

        dao.upsertForecasts(37.4170, -122.0890, google, listOf(DailyForecast(today, 69f, null, "Clear")), nowMs = now)

        val low = mutableListOf<Float?>()
        sql { st ->
            st.executeQuery("SELECT lowTemp FROM forecasts WHERE source = '$google' AND fetchedAt = $now").use { rs ->
                rs.next(); low += rs.getFloat(1).takeUnless { rs.wasNull() }
            }
        }
        assertEquals(listOf<Float?>(58.2f), low)
    }
}
