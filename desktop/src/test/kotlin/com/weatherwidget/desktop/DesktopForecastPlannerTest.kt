package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon
import com.weatherwidget.data.remote.ForecastSchedule
import com.weatherwidget.data.remote.SourceQuotaBlocks
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
 * The desktop forecast loop's planning against a real DB: [DesktopWeatherRepository] writes the
 * marker, [DesktopForecastPlanner] reads them back into what is due now and when the
 * source is next due, and [ForecastSchedule] turns that into the sleep. One rule for every source.
 * performance/261025-every-forecast-fetch-long.md
 */
@Category(ShortDuration::class)
class DesktopForecastPlannerTest {
    private val tempDbPath = Files.createTempFile("weather-forecast-planner-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val om = WeatherSource.OPEN_METEO.id
    private val nws = WeatherSource.NWS.id
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val minute = 60_000L
    private val hour = 60 * minute
    private val grace = ForecastSchedule.DUE_GRACE_MS

    private val raw = RawFetch(
        daily = listOf(DailyForecast(LocalDate.now().plusDays(1).toString(), 70f, 50f, "Cloudy")),
        hourly = (1..5).map { HourlyForecast(dateTime = (System.currentTimeMillis() / hour + it) * hour, temperature = 60f, condition = "Cloudy") },
    )

    /** Battery 90 %, unplugged, screen on unless given: every 4 h. */
    private fun planner(charging: Boolean = false, level: Int = 90, screenOn: Boolean = true) =
        DesktopForecastPlanner(dao, lat, lon, PowerDetector.PowerState(charging, level), screenOn)

    private suspend fun fetch(source: String = om, horizon: Horizon = Horizon.LONG) {
        coEvery { service.fetchForecast(any()) } returns raw
        DesktopWeatherRepository(service, dao, lat, lon, source).refresh(reason = "test", horizon = horizon)
    }

    @After
    fun teardown() {
        SourceQuotaBlocks.reset()
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `never fetched is LONG, due now, both parts`() {
        val now = System.currentTimeMillis()
        val plan = planner().plan(om, now)
        assertEquals(ForecastParts.BOTH, plan.parts)
        assertEquals(Horizon.LONG, plan.horizon)
        assertEquals(now, plan.nextDue!!.atMs)
    }

    @Test
    fun `after a fetch nothing is due, the next comes due in 4 h and the loop sleeps the 2 h cap`() = runTest {
        fetch()
        val now = System.currentTimeMillis()

        val plan = planner().plan(om, now)
        assertNull(plan.parts)
        assertNull(plan.horizon)
        val next = planner().next(listOf(om), now)!!
        assertEquals(om, next.sourceId)
        assertTrue("next due ≈ 4 h out", next.atMs in now + 4 * hour - grace - minute..now + 4 * hour)
        assertEquals(ForecastSchedule.Reason.CAP, ForecastSchedule.sleep(next, now, fetchFailed = false).reason)
    }

    @Test
    fun `five hours after a fetch the next is due, and it is LONG`() = runTest {
        // Was SHORT before 2026-10-25.
        fetch()
        val now = System.currentTimeMillis()
        assertNull(planner().plan(om, now + 3 * hour).horizon)
        assertEquals(Horizon.LONG, planner().plan(om, now + 5 * hour).horizon)
    }

    @Test
    fun `screen off makes it 8 h`() = runTest {
        fetch()
        val now = System.currentTimeMillis()
        val next = planner(screenOn = false).next(listOf(om), now)!!
        assertTrue(next.atMs - now in 8 * hour - grace - minute..8 * hour)
    }

    @Test
    fun `below 70 percent a day apart`() = runTest {
        fetch()
        val now = System.currentTimeMillis()
        val next = planner(level = 60).next(listOf(om), now)!!
        assertTrue(next.atMs - now in 24 * hour - grace - minute..24 * hour)
        assertNull(planner(level = 60).plan(om, now + 23 * hour).horizon)
    }

    @Test
    fun `every source follows the same rule, judged by its own markers`() = runTest {
        fetch(source = om)
        val now = System.currentTimeMillis()
        assertNull(planner().plan(om, now).parts)
        assertEquals(Horizon.LONG, planner().plan(nws, now).horizon)
    }

    /** performance/261012-forecast-loop-quota-blocked-part-is-not-due.md */
    @Test
    fun `a quota-blocked product is dropped from the fetch`() {
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + 3 * hour)
        assertEquals(ForecastParts.DAILY, planner().plan(google, now).parts)
        SourceQuotaBlocks.reset()
        SourceQuotaBlocks.block(google, ForecastProduct.DAILY, now + 3 * hour)
        assertEquals(ForecastParts.HOURLY, planner().plan(google, now).parts)
    }

    @Test
    fun `a source refused every product is not planned and is next due when the block ends`() {
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + 3 * hour)
        SourceQuotaBlocks.block(google, ForecastProduct.DAILY, now + 3 * hour)
        val plan = planner().plan(google, now)
        assertNull(plan.parts)
        assertEquals(now + 3 * hour, plan.nextDue!!.atMs)
        assertTrue(plan.logFragment!!, plan.logFragment!!.contains("quotaHeldMin=180"))
    }

    /**
     * Replays 2026-10-11 21:36 in the new terms: a source refused everything once made the loop wake
     * every minute to plan a fetch it could not make; now it sleeps toward the block's end.
     */
    @Test
    fun `replay - a fully blocked source no longer spins the loop every minute`() {
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + 145 * minute)
        SourceQuotaBlocks.block(google, ForecastProduct.DAILY, now + 145 * minute)

        val sleep = ForecastSchedule.sleep(planner().next(listOf(google), now), now, fetchFailed = false)
        assertTrue("slept ${sleep.ms / minute} min", sleep.ms >= 60 * minute)
        assertFalse(sleep.reason == ForecastSchedule.Reason.RETRY)
    }

    @Test
    fun `once the block ends the source is due again`() {
        val now = System.currentTimeMillis()
        SourceQuotaBlocks.block(google, ForecastProduct.HOURLY, now + hour)
        SourceQuotaBlocks.block(google, ForecastProduct.DAILY, now + hour)
        assertNull(planner().plan(google, now).parts)
        assertEquals(ForecastParts.BOTH, planner().plan(google, now + hour).parts)
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
