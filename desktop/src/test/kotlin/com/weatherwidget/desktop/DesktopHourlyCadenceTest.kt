package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate

/**
 * Desktop's hourly cadence: [DesktopWeatherRepository] fetches and stores only the planned parts,
 * keeps the daily cadence's `REFRESH` row out of hourly-only refreshes, and [DesktopHourlyWindow]
 * reads view age from `source_last_view`.
 * performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md
 */
@Category(ShortDuration::class)
class DesktopHourlyCadenceTest {
    private val tempDbPath = Files.createTempFile("weather-hourly-cadence-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val om = WeatherSource.OPEN_METEO.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()

    private val daily = listOf(DailyForecast(today.plusDays(1).toString(), 70f, 50f, "Cloudy"))
    private val hourly = (1..5).map { HourlyForecast(dateTime = (now / hour + it) * hour, temperature = 60f, condition = "Cloudy") }

    private fun repo() = DesktopWeatherRepository(service, dao, lat, lon, om)
    private fun window() = DesktopHourlyWindow(dao, om, lat, lon)

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `hourly-only refresh stores hours, no daily rows and no REFRESH row, and marks the hourly fetch`() = runTest {
        coEvery { service.fetchForecast(true, ForecastParts.HOURLY) } returns RawFetch(daily = daily, hourly = hourly)

        repo().refresh(now = now, reason = "test", parts = ForecastParts.HOURLY)

        assertTrue(dao.getHourlyForecasts(lat, lon, om, now, now + 10 * hour).isNotEmpty())
        assertTrue("daily rows untouched", dao.getDailyForecasts(lat, lon, om).isEmpty())
        assertNull("the daily cadence still sees no fetch", dao.getLastSuccessfulFetch(om))
        assertNotNull(window().lastHourlyFetch())
    }

    @Test
    fun `daily-only refresh asks for daily, stores it, and leaves the hourly cadence alone`() = runTest {
        coEvery { service.fetchForecast(false, ForecastParts.DAILY) } returns RawFetch(daily = daily)

        repo().refresh(now = now, reason = "test", parts = ForecastParts.DAILY)

        coVerify(exactly = 1) { service.fetchForecast(false, ForecastParts.DAILY) }
        assertFalse(dao.getDailyForecasts(lat, lon, om).isEmpty())
        assertNotNull(dao.getLastSuccessfulFetch(om))
        assertNull(window().lastHourlyFetch())
    }

    @Test
    fun `an hourly-limited refresh takes hourly only when it is due`() = runTest {
        coEvery { service.fetchForecast(false) } returns RawFetch(daily = daily, hourly = hourly)
        coEvery { service.fetchForecast(false, ForecastParts.DAILY) } returns RawFetch(daily = daily)

        // Never fetched hourly: due, so the full fetch.
        repo().refresh(now = now, reason = "test", hourlyLimited = true)
        coVerify(exactly = 1) { service.fetchForecast(false) }
        assertNotNull(window().lastHourlyFetch())

        // Just fetched: the displayed source's shortest cadence is 4 h, so daily only.
        repo().refresh(now = now + 60_000L, reason = "test", hourlyLimited = true)
        coVerify(exactly = 1) { service.fetchForecast(false, ForecastParts.DAILY) }
    }

    @Test
    fun `a recorded view is the source's last view, read back as view age`() {
        dao.recordLastView(om, now - 30 * hour)
        dao.recordLastView(om, now - 2 * hour)
        val recency = DesktopHourlyWindow.recency(dao, listOf(om, WeatherSource.NWS.id), now)
        assertEquals(now - 2 * hour, recency.of(om))
        assertNull(recency.of(WeatherSource.NWS.id))
        val decision = DesktopHourlyWindow(dao, om, lat, lon) { PowerDetector.PowerState(true, 90) }
            .hourlyDecision(isDisplayed = false, nowMs = now)!!
        assertEquals(4, decision.intervalHours)
        assertTrue("never fetched", decision.due)
    }

    @Test
    fun `the last-view table exists at the current schema version`() {
        dao.recordLastView(om, 1L)
        assertEquals(mapOf(om to 1L), dao.lastViews())
        assertEquals(33, DesktopWeatherDatabase.SCHEMA_VERSION)
    }
}
