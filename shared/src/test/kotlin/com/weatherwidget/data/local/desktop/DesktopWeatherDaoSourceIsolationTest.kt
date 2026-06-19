package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyExtreme
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the core invariant: data read for a source must come ONLY from that source. The single
 * sanctioned exception is the climate-normal gap (`Generic`) for FUTURE hours in getHourlyHistory.
 */
class DesktopWeatherDaoSourceIsolationTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 40.0
    private val lon = -75.0
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_isolation_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `getLatestHourly returns only the requested source`() {
        dao.upsertHourlyForecasts(lat, lon, "NWS", listOf(HourlyForecast(now, 70f, "Sunny", source = "NWS")))
        dao.upsertHourlyForecasts(lat, lon, "OPEN_METEO", listOf(HourlyForecast(now, 50f, "Cloudy", source = "OPEN_METEO")))

        val nws = dao.getLatestHourly(lat, lon, "NWS", 10_000)
        val om = dao.getLatestHourly(lat, lon, "OPEN_METEO", 10_000)

        assertEquals(listOf(70f), nws.map { it.temperature })
        assertEquals(listOf(50f), om.map { it.temperature })
    }

    @Test
    fun `getDailyForecasts returns only the requested source`() {
        dao.upsertForecasts(lat, lon, "NWS", listOf(DailyForecast("2026-06-19", 80f, 60f, "Sunny")))
        dao.upsertForecasts(lat, lon, "OPEN_METEO", listOf(DailyForecast("2026-06-19", 51f, 41f, "Cloudy")))

        assertEquals(80f, dao.getDailyForecasts(lat, lon, "NWS").single().highTemp)
        assertEquals(51f, dao.getDailyForecasts(lat, lon, "OPEN_METEO").single().highTemp)
    }

    @Test
    fun `getDailyActuals returns only the requested source`() {
        val date = java.time.LocalDate.parse("2026-06-18")
        val epoch = date.toEpochDay() * 86_400_000L
        dao.upsertDailyExtremes(listOf(
            DailyExtreme(epoch, "NWS", lat, lon, 79f, 61f, "Sunny", now),
            DailyExtreme(epoch, "OPEN_METEO", lat, lon, 52f, 42f, "Cloudy", now),
        ))

        val nws = dao.getDailyActuals(epoch, epoch, lat, lon, "NWS")
        val om = dao.getDailyActuals(epoch, epoch, lat, lon, "OPEN_METEO")

        assertEquals(79f, nws.values.single().highTemp)
        assertEquals(52f, om.values.single().highTemp)
    }

    @Test
    fun `getHourlyHistory isolates by source but allows Generic only for future hours`() {
        val hourMs = 3_600_000L
        val pastTs = now - 6 * hourMs
        val futureTs = now + 6 * hourMs
        val bucket = now

        dao.upsertHourlyForecastHistory(lat, lon, "NWS", bucket, listOf(
            HourlyForecast(pastTs, 70f, "Sunny", source = "NWS"),
            HourlyForecast(futureTs, 72f, "Sunny", source = "NWS"),
        ))
        dao.upsertHourlyForecastHistory(lat, lon, "OPEN_METEO", bucket, listOf(
            HourlyForecast(futureTs, 50f, "Cloudy", source = "OPEN_METEO"),
        ))
        // Generic climate-normal rows: the future one is the sanctioned exception; the past one is NOT.
        dao.upsertHourlyForecastHistory(lat, lon, "Generic", bucket, listOf(
            HourlyForecast(pastTs, 10f, "Generic", source = "Generic"),
            HourlyForecast(futureTs, 99f, "Generic", source = "Generic"),
        ))

        val history = dao.getHourlyHistory(lat, lon, "NWS", now - 12 * hourMs, now + 12 * hourMs, now)
        val temps = history.map { it.temperature }.toSet()

        assertTrue("NWS past kept", temps.contains(70f))
        assertTrue("NWS future kept", temps.contains(72f))
        assertTrue("Generic future allowed", temps.contains(99f))
        assertTrue("Open-Meteo excluded", !temps.contains(50f))
        assertTrue("Generic past excluded", !temps.contains(10f))
    }
}
