package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The wake catch-up publishes before it fills history: `refreshWithOutcome(deferObservationWindow
 * = true)` asks NWS for the recent observation window only and never touches the 7-day pull, and
 * [DesktopWeatherRepository.refreshObservationWindow] stores that pull afterwards. Real DB, mocked
 * network seam. performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md.
 */
@Category(ShortDuration::class)
class DeferredObservationWindowTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val weatherService = mockk<WeatherApiClient>(relaxed = true)

    private val lat = 37.4220
    private val lon = -122.0841
    private val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-deferred-window-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun repo(source: String = "NWS") =
        DesktopWeatherRepository(weatherService, dao, lat, lon, source, currentTimeMillis = { now })

    private fun reading(station: String, ts: Long, temp: Float) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = ts,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = "NWS",
    )

    private fun stubForecast(recentOnly: Boolean) {
        coEvery { weatherService.fetchForecast(recentOnly) } returns RawFetch(
            hourly = listOf(HourlyForecast(now, 72f, "Clear")),
            rawObservations = listOf(reading("KNUQ", now - 10 * 60_000L, 71f)),
        )
    }

    @Test
    fun `deferred refresh publishes from the recent window without the 7-day pull`() = runTest {
        stubForecast(recentOnly = true)

        val outcome = repo().refreshWithOutcome(now, deferObservationWindow = true)

        assertEquals(1, outcome.snapshot.raw.hourly.size)
        coVerify(exactly = 1) { weatherService.fetchForecast(true) }
        coVerify(exactly = 0) { weatherService.fetchForecast(false) }
        coVerify(exactly = 0) { weatherService.fetchObservationHistory(any()) }
        val refreshRow = dao.getLatestLogByTagAndMessagePrefix("REFRESH", "source=NWS")
        assertNotNull(refreshRow)
        assertTrue(refreshRow!!.message, refreshRow.message.endsWith("obsWindow=deferred"))
    }

    @Test
    fun `default refresh is unchanged - full window, no deferral marker`() = runTest {
        stubForecast(recentOnly = false)

        repo().refreshWithOutcome(now)

        coVerify(exactly = 1) { weatherService.fetchForecast(false) }
        val refreshRow = dao.getLatestLogByTagAndMessagePrefix("REFRESH", "source=NWS")!!
        assertTrue(refreshRow.message, !refreshRow.message.contains("obsWindow"))
    }

    @Test
    fun `observation window phase stores the 7-day history`() = runTest {
        val history = (1..48).map { h -> reading("KSJC", now - h * 3600_000L, 60f + h % 10) }
        coEvery { weatherService.fetchObservationHistory(DesktopWeatherService.HISTORY_DAYS) } returns history

        val stored = repo().refreshObservationWindow(now)

        assertEquals(48, stored)
        val inDb = dao.getObservationsInRange(now - 49 * 3600_000L, now, lat, lon)
            .count { it.stationId == "KSJC" }
        assertEquals(48, inDb)
        val row = dao.getLatestLogByTagAndMessagePrefix("OBS_WINDOW_REFRESH", "source=NWS rows=48")
        assertNotNull(row)
    }

    @Test
    fun `observation window failure is logged and swallowed - the forecast is already out`() = runTest {
        coEvery { weatherService.fetchObservationHistory(any()) } throws
            EOFException("Invalid chunk: content block of size 49152 ended unexpectedly")

        val stored = repo().refreshObservationWindow(now)

        assertEquals(-1, stored)
        val row = dao.getLatestLogByTagAndMessagePrefix("OBS_WINDOW_REFRESH", "source=NWS failed")
        assertNotNull(row)
        assertEquals("WARN", row!!.level)
    }

    @Test
    fun `non-NWS sources have no deferred window and make no request`() = runTest {
        assertEquals(0, repo(source = "OPEN_METEO").refreshObservationWindow(now))
        coVerify(exactly = 0) { weatherService.fetchObservationHistory(any()) }
    }
}
