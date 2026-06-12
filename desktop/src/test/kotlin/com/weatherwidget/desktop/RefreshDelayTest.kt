package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RefreshDelayTest {

    @Test
    fun `returns default delay for null hourly`() {
        assertEquals(15 * 60 * 1000L, computeRefreshDelayMs(null))
    }

    @Test
    fun `returns default delay for empty hourly`() {
        assertEquals(15 * 60 * 1000L, computeRefreshDelayMs(emptyList()))
    }

    @Test
    fun `returns shorter delay for high temperature swing`() {
        val hourly = listOf(
            HourlyForecast(0, 60f, "Clear"),
            HourlyForecast(3600000, 72f, "Clear"), // 12 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        // getUpdatesPerHour returns 4 for maxDiff >= 8, so 3600000/4 = 900000 (15 min)
        assertTrue(delay >= 10 * 60 * 1000L)
        assertTrue(delay <= 15 * 60 * 1000L)
    }

    @Test
    fun `returns default delay for low temperature swing`() {
        val hourly = listOf(
            HourlyForecast(0, 70f, "Clear"),
            HourlyForecast(3600000, 71f, "Clear"), // 1 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        assertEquals(60 * 60 * 1000L, delay) // 1 update per hour
    }

    @Test
    fun `respects minimum delay floor`() {
        // Even with extreme swing, delay should be >= 10 min
        val hourly = listOf(
            HourlyForecast(0, 40f, "Clear"),
            HourlyForecast(3600000, 80f, "Clear"), // 40 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        assertTrue(delay >= 10 * 60 * 1000L)
    }

    @Test
    fun `launch refresh uses full forecast when cache is missing`() {
        val action = determineLaunchRefreshAction(
            cachePresent = false,
            lastObservationFetchMs = 900_000L,
            lastForecastFetchMs = 900_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.FULL_FORECAST, action)
    }

    @Test
    fun `launch refresh skips network when cached forecast and observations are fresh`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 500_000L,
            lastForecastFetchMs = 990_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.NONE, action)
    }

    @Test
    fun `launch refresh uses observations only when observations stale but forecast fresh`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 300_000L,
            lastForecastFetchMs = 990_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.OBSERVATIONS, action)
    }

    @Test
    fun `launch refresh uses observations only when cached observation fetch is unknown`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = null,
            lastForecastFetchMs = 990_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.OBSERVATIONS, action)
    }

    @Test
    fun `launch refresh pulls full forecast when cached forecast is stale even if observations are fresh`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 9_900_000L, // 100s old: fresh
            lastForecastFetchMs = 1_000_000L,    // ~2.5h old: stale (> 60 min)
            nowMs = 10_000_000L,
        )

        assertEquals(LaunchRefreshAction.FULL_FORECAST, action)
    }

    @Test
    fun `launch refresh pulls full forecast when cached forecast fetch is unknown`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 990_000L,
            lastForecastFetchMs = null,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.FULL_FORECAST, action)
    }

    @Test
    fun `resume signal line recognizes logind wake`() {
        assertTrue(
            isResumeSignalLine(
                "/org/freedesktop/login1: org.freedesktop.login1.Manager.PrepareForSleep (false)"
            )
        )
    }

    @Test
    fun `resume signal line ignores the sleep-imminent signal`() {
        assertFalse(
            isResumeSignalLine(
                "/org/freedesktop/login1: org.freedesktop.login1.Manager.PrepareForSleep (true)"
            )
        )
    }

    @Test
    fun `resume signal line ignores unrelated dbus traffic`() {
        assertFalse(isResumeSignalLine("/org/freedesktop/login1: some.other.Signal (false)"))
    }

    @Test
    fun `suspend jump detected when wall-clock gap far exceeds the heartbeat`() {
        // 6h elapsed against a 30s heartbeat => suspended.
        assertTrue(isSuspendJump(HEARTBEAT_INTERVAL_MS, 6 * 60 * 60 * 1000L, SUSPEND_JUMP_SLACK_MS))
    }

    @Test
    fun `suspend jump not detected for normal scheduling jitter`() {
        // Heartbeat ran a few seconds late: well within slack, not a suspend.
        assertFalse(isSuspendJump(HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS + 5_000L, SUSPEND_JUMP_SLACK_MS))
    }

    @Test
    fun `observations only refresh skips unsupported current-only sources`() = runTest {
        val service = DesktopWeatherService(
            latitude = 37.4220,
            longitude = -122.0841,
            weatherSource = WeatherSource.TOMORROW_IO.id,
        )

        try {
            val result = service.fetchObservationsOnly()

            assertNull(result.currentTemp)
            assertTrue(result.daily.isEmpty())
            assertTrue(result.hourly.isEmpty())
            assertTrue(result.rawObservations.isEmpty())
        } finally {
            service.close()
        }
    }
}
