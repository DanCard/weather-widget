package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager

/**
 * `hourly_forecasts.issuedAt` on real SQLite: it round-trips through both hourly writers and the
 * stitched read, and a v41 database gains the column with its rows kept.
 * plans/261114-nws-issuance-time-prior.md
 */
@Category(ShortDuration::class)
class DesktopHourlyIssuedAtStorageTest {
    private val path = Files.createTempFile("weather-issued-at-test", ".db")
    private val lat = 37.417
    private val lon = -122.089
    private val hour = 1_792_954_800_000L
    private val hourMs = 3_600_000L

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    @Test
    fun `issuedAt round-trips and the stitched read picks by it`() {
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        // Evening fetch (9 h before) and a wake-up fetch 10 min after the hour of an issuance 30 min before it.
        dao.upsertHourlyForecastHistory(lat, lon, "NWS", hour - 9 * hourMs, listOf(
            HourlyForecast(hour, 50f, "Cloudy", precipProbability = 4, fetchedAt = hour - 9 * hourMs, issuedAt = hour - 10 * hourMs),
        ))
        dao.upsertHourlyForecasts(lat, lon, "NWS", listOf(
            HourlyForecast(hour, 55f, "Cloudy", precipProbability = 25, fetchedAt = hour + 10 * 60_000L, issuedAt = hour - 30 * 60_000L),
        ))
        val row = dao.getHourlyWithHistory(lat, lon, "NWS", hour - hourMs, hour + hourMs, nowMs = hour + 2 * hourMs).single { it.dateTime == hour }
        println("stitched: rain=${row.precipProbability} issuedAt=${row.issuedAt} madeAt=${row.madeAt}")
        assertEquals(hour - 30 * 60_000L, row.issuedAt)
        assertEquals(25, row.precipProbability)
    }

    @Test
    fun `a v41 database gains the column and keeps its rows`() {
        DesktopWeatherDatabase(path).initialize()
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, " +
                        "timestampToGroupPredictions, precipProbability, fetchedAt) VALUES ($hour, $lat, $lon, 50.0, 'Cloudy', 'NWS', 1, 40, 1)",
                )
                st.execute("ALTER TABLE hourly_forecasts DROP COLUMN ${DesktopWeatherDatabase.HOURLY_ISSUED_AT_COLUMN}")
                st.execute("PRAGMA user_version = 41")
            }
        }

        DesktopWeatherDatabase(path).initialize()

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, it.getInt(1)) }
                val columns = mutableSetOf<String>()
                st.executeQuery("PRAGMA table_info(hourly_forecasts)").use { while (it.next()) columns += it.getString("name") }
                assertTrue(columns.contains("issuedAt"))
                st.executeQuery("SELECT precipProbability, issuedAt FROM hourly_forecasts").use {
                    assertTrue(it.next())
                    assertEquals(40, it.getInt(1))
                    it.getLong(2); assertTrue("old rows carry no issuance", it.wasNull())
                }
            }
        }
    }
}
