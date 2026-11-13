package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
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
 * `daily_history.forecastDayPrecipPeakAt` on real SQLite: it round-trips beside the frozen chance, and
 * a v40 database gains the column with its rows kept (plans/261113-today-rain-peak-never-hidden.md).
 */
@Category(ShortDuration::class)
class DesktopFrozenDayPeakStorageTest {
    private val path = Files.createTempFile("weather-day-peak-test", ".db")
    private val lat = 37.417
    private val lon = -122.089
    private val date = 1794441600000L
    private val noon = 1794513600000L

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    @Test
    fun `the frozen peak round-trips with its chance`() {
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date, "NWS", lat, lon, 63f, 54f, "Rain", updatedAt = 1_000L,
                    forecastDayPrecipChance = 99, forecastDayPrecipPeakAt = noon,
                ),
            ),
        )
        val row = dao.getExtremesInRange(date, date, lat, lon).single()
        println("round-trip: chance=${row.forecastDayPrecipChance} peakAt=${row.forecastDayPrecipPeakAt}")
        assertEquals(99, row.forecastDayPrecipChance)
        assertEquals(noon, row.forecastDayPrecipPeakAt)
    }

    @Test
    fun `a v40 database gains the column and keeps its frozen chance`() {
        DesktopWeatherDatabase(path).initialize()
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO daily_history (date, source, locationLat, locationLon, computedHighTemp, computedLowTemp, condition, updatedAt, " +
                        "forecastDayPrecipChance) VALUES ($date, 'NWS', $lat, $lon, 63.0, 54.0, 'Rain', 1, 99)",
                )
                st.execute("ALTER TABLE daily_history DROP COLUMN ${DesktopWeatherDatabase.DAILY_HISTORY_DAY_PRECIP_PEAK_COLUMN}")
                st.execute("PRAGMA user_version = 40")
            }
        }

        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, it.getInt(1)) }
                val columns = mutableSetOf<String>()
                st.executeQuery("PRAGMA table_info(daily_history)").use { while (it.next()) columns += it.getString("name") }
                assertTrue(columns.contains("forecastDayPrecipPeakAt"))
            }
        }
        val kept = dao.getExtremesInRange(date, date, lat, lon).single()
        println("v40 kept: chance=${kept.forecastDayPrecipChance} peakAt=${kept.forecastDayPrecipPeakAt}")
        assertEquals(99, kept.forecastDayPrecipChance)
        assertNull(kept.forecastDayPrecipPeakAt)
    }
}
