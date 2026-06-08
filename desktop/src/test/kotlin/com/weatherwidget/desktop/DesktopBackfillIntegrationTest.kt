package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopBackfillIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val weatherService = mockk<DesktopWeatherService>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val source = "NWS"

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-backfill-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `fresh install triggers backfill and stitches gaps`() = runTest {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L

        // 1. Mock fetchForecast (Primary NWS data, cloudCover is null)
        val liveHourly = listOf(
            HourlyForecast(baseHour, 70f, "Clear", cloudCover = null, source = "NWS"),
            HourlyForecast(baseHour + 3600_000L, 72f, "Clear", cloudCover = null, source = "NWS")
        )
        coEvery { weatherService.fetchForecast() } returns ForecastResult(
            currentTemp = 70f,
            hourly = liveHourly,
            daily = emptyList()
        )

        // 2. Mock fetchHistory (Open-Meteo backfill data, cloudCover populated)
        val backfillHourly = listOf(
            HourlyForecast(baseHour, 68f, "Cloudy", cloudCover = 85, source = "OPEN_METEO"),
            HourlyForecast(baseHour + 3600_000L, 69f, "Cloudy", cloudCover = 90, source = "OPEN_METEO")
        )
        coEvery { weatherService.fetchHistory(3) } returns ForecastResult(
            hourly = backfillHourly
        )

        // 3. Trigger refresh
        repository.refresh()

        // 4. Verify fetchHistory was called
        coVerify(exactly = 1) { weatherService.fetchHistory(3) }

        // 5. Load cached and verify stitching
        val result = repository.loadCached()
        assertNotNull(result)
        
        result!!.hourly.forEach { h ->
            println("DEBUG: hourly dateTime=${h.dateTime} temp=${h.temperature} cloudCover=${h.cloudCover} source=${h.source}")
        }

        val stitched = result.hourly.associateBy { it.dateTime }
        
        // Hour 0: NWS values win for temp/condition, but cloudCover is repaired from history
        val h0 = stitched[baseHour]
        assertNotNull(h0)
        assertEquals(70f, h0!!.temperature)
        assertEquals(85, h0.cloudCover)
        
        // Hour 1: same repair
        val h1 = stitched[baseHour + 3600_000L]
        assertNotNull(h1)
        assertEquals(72f, h1!!.temperature)
        assertEquals(90, h1.cloudCover)
    }

    @Test
    fun `populated history skips backfill`() = runTest {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L

        // 1. Pre-seed database with enough history (> 24 hours)
        val existingHistory = (0 until 30).map { i ->
            HourlyForecast(baseHour - i * 3600_000L, 60f + i, "Clear", cloudCover = 50, source = "NWS")
        }
        dao.upsertHourlyForecastHistory(lat, lon, source, now - 4 * 3600_000L, existingHistory)

        // 2. Mock fetchForecast
        coEvery { weatherService.fetchForecast() } returns ForecastResult(
            currentTemp = 70f,
            hourly = listOf(HourlyForecast(baseHour, 70f, "Clear", source = "NWS")),
            daily = emptyList()
        )

        // 3. Trigger refresh
        repository.refresh()

        // 4. Verify fetchHistory was NOT called
        coVerify(exactly = 0) { weatherService.fetchHistory(any()) }
    }

    @Test
    fun `backfill occurs only once per session`() = runTest {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L

        // 1. Mock fetchForecast
        coEvery { weatherService.fetchForecast() } returns ForecastResult(
            currentTemp = 70f,
            hourly = listOf(HourlyForecast(baseHour, 70f, "Clear", source = "NWS")),
            daily = emptyList()
        )

        // 2. Mock fetchHistory to return empty (simulating failure or no data)
        coEvery { weatherService.fetchHistory(3) } returns ForecastResult(hourly = emptyList())

        // 3. First refresh
        repository.refresh()
        coVerify(exactly = 1) { weatherService.fetchHistory(3) }

        // 4. Second refresh in the same session (repository instance)
        repository.refresh()
        
        // Still exactly 1 call because of hasAttemptedBackfill flag
        coVerify(exactly = 1) { weatherService.fetchHistory(3) }
    }

    @Test
    fun `refresh returns stitched data immediately`() = runTest {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L

        // 1. Mock fetchForecast (Primary NWS data, cloudCover is null)
        val liveHourly = listOf(
            HourlyForecast(baseHour, 70f, "Clear", cloudCover = null, source = "NWS")
        )
        coEvery { weatherService.fetchForecast() } returns ForecastResult(
            currentTemp = 70f,
            hourly = liveHourly,
            daily = emptyList()
        )

        // 2. Mock fetchHistory (Open-Meteo backfill data, cloudCover populated)
        val backfillHourly = listOf(
            HourlyForecast(baseHour, 68f, "Cloudy", cloudCover = 85, source = "OPEN_METEO")
        )
        coEvery { weatherService.fetchHistory(3) } returns ForecastResult(
            hourly = backfillHourly
        )

        // 3. Trigger refresh and capture the returned result
        val result = repository.refresh()

        // 4. Verify the returned result has the stitched cloud cover
        val h0 = result.hourly.find { it.dateTime == baseHour }
        assertNotNull("Hourly forecast should exist in returned result", h0)
        assertEquals("Temperature should come from live fetch", 70f, h0!!.temperature)
        assertEquals("Cloud cover should be repaired from backfill immediately", 85, h0.cloudCover)
    }
}
