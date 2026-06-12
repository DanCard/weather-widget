package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyForecast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

class DesktopWeatherDaoTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-test-dao", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `getLastSuccessfulFetch returns null when no logs exist`() {
        assertNull(dao.getLastSuccessfulFetch())
        assertNull(dao.getLastSuccessfulFetch("NWS"))
    }

    @Test
    fun `getLastSuccessfulFetch retrieves latest refresh matching specific source`() {
        // Write a warning log (should be ignored)
        dao.log(tag = "REFRESH_FAIL", message = "source=NWS offline", level = "WARN")
        
        // Write a success NWS log
        val nwsTime = 1000L
        dao.log(tag = "REFRESH", message = "source=NWS hourly=156 daily=7 obs=1726 extremes=9", level = "INFO")
        // We override timestamp for test correctness via SQL since DAO log uses System.currentTimeMillis()
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $nwsTime WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // Write a success Open-Meteo log later
        val omTime = 2000L
        dao.log(tag = "REFRESH", message = "source=OPEN_METEO hourly=156 daily=7 obs=1726 extremes=9", level = "INFO")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $omTime WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // 1. Generic check should return the latest overall (2000L)
        assertEquals(2000L, dao.getLastSuccessfulFetch())

        // 2. Specific source checks
        assertEquals(1000L, dao.getLastSuccessfulFetch("NWS"))
        assertEquals(2000L, dao.getLastSuccessfulFetch("OPEN_METEO"))
        assertNull(dao.getLastSuccessfulFetch("SILURIAN"))
    }

    @Test
    fun `getDailyForecasts repairs degenerate today using last genuine forecast`() {
        val lat = 37.0
        val lon = -122.0
        val source = "NWS"
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()

        // An earlier, genuine forecast for today (real high/low spread) plus a future day.
        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 91f, lowTemp = 62f, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 87f, lowTemp = 60f, condition = "Sunny"),
        ))
        // Age that batch so the degenerate fetch below becomes the latest batch.
        setForecastBatchStamp(1000L)

        // The latest fetch collapsed today's forecast to high == low (the NWS late-day bug).
        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 92f, lowTemp = 92f, condition = "Sunny"),
            DailyForecast(date = tomorrow, highTemp = 87f, lowTemp = 60f, condition = "Sunny"),
        ))

        val days = dao.getDailyForecasts(lat, lon, source).associateBy { it.date }
        // Today is repaired to the last genuine historical forecast, not the degenerate latest.
        assertEquals(91f, days.getValue(today).highTemp)
        assertEquals(62f, days.getValue(today).lowTemp)
        // The genuine future day is untouched.
        assertEquals(87f, days.getValue(tomorrow).highTemp)
        assertEquals(60f, days.getValue(tomorrow).lowTemp)
    }

    @Test
    fun `getDailyForecasts keeps degenerate day when no genuine forecast exists`() {
        val lat = 37.0
        val lon = -122.0
        val source = "NWS"
        val today = LocalDate.now(ZoneOffset.UTC).toString()

        dao.upsertForecasts(lat, lon, source, listOf(
            DailyForecast(date = today, highTemp = 92f, lowTemp = 92f, condition = "Sunny"),
        ))

        val day = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals(92f, day.highTemp)
        assertEquals(92f, day.lowTemp)
    }

    /** Force every stored forecast row to a fixed batch/fetched stamp so a later insert outranks it. */
    private fun setForecastBatchStamp(value: Long) {
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE forecasts SET batchFetchedAt = $value, fetchedAt = $value")
            }
        }
    }
}
