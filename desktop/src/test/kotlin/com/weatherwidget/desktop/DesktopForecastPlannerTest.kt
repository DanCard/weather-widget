package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastSchedule
import com.weatherwidget.data.remote.ForecastSchedule.Part
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate

/**
 * The desktop forecast loop's planning against a real DB: [DesktopWeatherRepository] moves the
 * `REFRESH` / `HOURLY_FETCH` markers, [DesktopForecastPlanner] reads them back into what is due now and
 * when each part is next due, and [ForecastSchedule] turns that into the sleep.
 * performance/261011-desktop-forecast-loop-sleep-until-due.md
 */
@Category(ShortDuration::class)
class DesktopForecastPlannerTest {
    private val tempDbPath = Files.createTempFile("weather-forecast-planner-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val om = WeatherSource.OPEN_METEO.id
    private val nws = WeatherSource.NWS.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val minute = 60_000L
    private val hour = 60 * minute
    private val grace = ForecastSchedule.DUE_GRACE_MS

    private val raw = RawFetch(
        daily = listOf(DailyForecast(LocalDate.now().plusDays(1).toString(), 70f, 50f, "Cloudy")),
        hourly = (1..5).map { HourlyForecast(dateTime = (System.currentTimeMillis() / hour + it) * hour, temperature = 60f, condition = "Cloudy") },
    )

    private fun planner(charging: Boolean = true, level: Int = 90, screenOn: Boolean = true) =
        DesktopForecastPlanner(dao, lat, lon, PowerDetector.PowerState(charging, level), screenOn)

    @After
    fun teardown() {
        SourceQuotaBlocks.reset()
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `never fetched is due now for both parts`() {
        val now = System.currentTimeMillis()
        val plan = planner().plan(om, isDisplayed = true, nowMs = now)
        assertEquals(ForecastParts.BOTH, plan.parts)
        assertEquals(listOf(Part.DAILY to now, Part.HOURLY to now), plan.dues.map { it.part to it.atMs })
    }

    @Test
    fun `after a full refresh nothing is due and the loop sleeps the 2 h cap`() = runTest {
        coEvery { service.fetchForecast(false) } returns raw
        DesktopWeatherRepository(service, dao, lat, lon, om).refresh(reason = "test")
        val now = System.currentTimeMillis()

        val plan = planner().plan(om, isDisplayed = true, nowMs = now)
        assertNull(plan.parts)
        val next = planner().next(listOf(om to true), now)!!
        assertTrue("next due ≈ 4 h out", next.atMs in now + 4 * hour - grace - minute..now + 4 * hour)
        assertEquals(ForecastSchedule.Reason.CAP, ForecastSchedule.sleep(next, now, fetchFailed = false).reason)
    }

    @Test
    fun `screen off lengthens the displayed daily to 6 h on the charger, hourly stays 4 h`() = runTest {
        coEvery { service.fetchForecast(false) } returns raw
        DesktopWeatherRepository(service, dao, lat, lon, om).refresh(reason = "test")
        val now = System.currentTimeMillis()

        val dues = planner(screenOn = false).plan(om, isDisplayed = true, nowMs = now).dues.associate { it.part to it.atMs - now }
        assertTrue(dues.getValue(Part.DAILY) in 6 * hour - grace - minute..6 * hour)
        assertTrue(dues.getValue(Part.HOURLY) in 4 * hour - grace - minute..4 * hour)
    }

    @Test
    fun `an hourly-only refresh moves the hourly due time and leaves daily due`() = runTest {
        coEvery { service.fetchForecast(true, ForecastParts.HOURLY) } returns raw
        DesktopWeatherRepository(service, dao, lat, lon, om).refresh(reason = "test", parts = ForecastParts.HOURLY)
        val now = System.currentTimeMillis()

        val plan = planner().plan(om, isDisplayed = true, nowMs = now)
        assertEquals(ForecastParts.DAILY, plan.parts)
        val hourlyDue = plan.dues.single { it.part == Part.HOURLY }.atMs
        assertTrue(hourlyDue > now + 3 * hour)
    }

    @Test
    fun `at or below 50 percent an unviewed other source has nothing scheduled`() {
        val now = System.currentTimeMillis()
        dao.recordLastView(nws, now - 100 * hour)
        val plan = planner(charging = false, level = 40).plan(nws, isDisplayed = false, nowMs = now)
        assertNull(plan.parts)
        assertTrue("no daily cadence, no hourly cadence", plan.dues.isEmpty())
        assertEquals(ForecastSchedule.Reason.CAP, ForecastSchedule.sleep(planner(false, 40).next(listOf(nws to false), now), now, false).reason)
    }

    private val google = WeatherSource.GOOGLE_WEATHER.id

    /** Google with a fresh daily row and its hours never fetched: hourly due now, daily not. */
    private suspend fun googleDailyFreshHourlyOverdue() {
        coEvery { service.fetchForecast(any(), any()) } returns raw.copy(hourly = emptyList())
        coEvery { service.fetchForecast(any(), any(), any()) } returns raw.copy(hourly = emptyList())
        DesktopWeatherRepository(service, dao, lat, lon, google).refresh(reason = "test", parts = ForecastParts.DAILY)
    }

    /** performance/261012-forecast-loop-quota-blocked-part-is-not-due.md */
    @Test
    fun `a quota-blocked hourly part is not planned and is next due when the block ends`() = runTest {
        googleDailyFreshHourlyOverdue()
        val now = System.currentTimeMillis()
        assertEquals("unblocked, FULL window: hourly due takes daily too", ForecastParts.BOTH, planner().plan(google, true, now).parts)

        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + 3 * hour)
        val plan = planner().plan(google, isDisplayed = true, nowMs = now)
        assertNull("blocked hourly is not due and does not drag daily along", plan.parts)
        assertEquals(now + 3 * hour, plan.dues.single { it.part == Part.HOURLY }.atMs)
        assertTrue(plan.hourlyLog!!.contains("quotaHeldMin=180"))
    }

    @Test
    fun `a quota-blocked hourly part leaves a due daily part to run alone`() {
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + 3 * hour)
        assertEquals(ForecastParts.DAILY, planner().plan(google, isDisplayed = true, nowMs = now).parts)
    }

    @Test
    fun `a quota-blocked daily part leaves a due hourly part to run alone, never upgraded to both`() {
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.DAILY, now + 3 * hour)
        val plan = planner().plan(google, isDisplayed = true, nowMs = now)
        assertEquals(ForecastParts.HOURLY, plan.parts)
        assertEquals(now + 3 * hour, plan.dues.single { it.part == Part.DAILY }.atMs)
    }

    /**
     * Replays 2026-10-11 21:36: charging, Google displayed, hours overdue in a FULL window,
     * `forecast/hours` refused until midnight PT. The loop slept 1 min and paid for daily + current on
     * every wake; now it sleeps toward the block's end.
     */
    @Test
    fun `replay - a blocked hourly part no longer spins the loop every minute`() = runTest {
        googleDailyFreshHourlyOverdue()
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + 145 * minute)

        val sleep = ForecastSchedule.sleep(planner().next(listOf(google to true), now), now, fetchFailed = false)
        assertTrue("slept ${sleep.ms / minute} min", sleep.ms >= 60 * minute)
        assertFalse(sleep.reason == ForecastSchedule.Reason.RETRY)
    }

    @Test
    fun `once the block ends the hourly part is due again`() = runTest {
        googleDailyFreshHourlyOverdue()
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + hour)
        assertNull(planner().plan(google, true, now).parts)
        assertEquals(ForecastParts.BOTH, planner().plan(google, true, now + hour).parts)
    }

    @Test
    fun `UPower lines that can change the cadence are recognised`() {
        assertTrue(isPowerChangeSignalLine("/org/freedesktop/UPower/devices/line_power_AC: org.freedesktop.DBus.Properties.PropertiesChanged ('org.freedesktop.UPower.Device', {'Online': <true>}, @as [])"))
        assertTrue(isPowerChangeSignalLine("/org/freedesktop/UPower: org.freedesktop.DBus.Properties.PropertiesChanged ('org.freedesktop.UPower', {'OnBattery': <false>}, @as [])"))
        assertTrue(isPowerChangeSignalLine("/org/freedesktop/UPower/devices/battery_BAT0: org.freedesktop.DBus.Properties.PropertiesChanged ('org.freedesktop.UPower.Device', {'Percentage': <79.0>, 'TimeToEmpty': <<int64 9000>>}, @as [])"))
        assertFalse(isPowerChangeSignalLine("/org/freedesktop/UPower/devices/battery_BAT0: org.freedesktop.DBus.Properties.PropertiesChanged ('org.freedesktop.UPower.Device', {'TimeToEmpty': <<int64 9000>>}, @as [])"))
        assertFalse(isPowerChangeSignalLine("Monitoring signals on object /org/freedesktop/UPower owned by :1.12"))
    }
}
