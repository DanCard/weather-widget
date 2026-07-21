package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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
    fun `network restored line recognizes StateChanged to connected global`() {
        assertTrue(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.NetworkManager.StateChanged (uint32 70,)"
            )
        )
    }

    @Test
    fun `network restored line recognizes Connectivity full property change`() {
        assertTrue(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.DBus.Properties.PropertiesChanged " +
                    "('org.freedesktop.NetworkManager', {'Connectivity': <uint32 4>, 'State': <uint32 70>}, @as [])"
            )
        )
    }

    @Test
    fun `network restored line ignores disconnected and connecting states`() {
        assertFalse(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.NetworkManager.StateChanged (uint32 20,)"
            )
        )
        assertFalse(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.NetworkManager.StateChanged (uint32 40,)"
            )
        )
        // Digit lookahead: a larger number containing "70" as a prefix must not match.
        assertFalse(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.NetworkManager.StateChanged (uint32 700,)"
            )
        )
    }

    @Test
    fun `network restored line ignores partial connectivity and unrelated signals`() {
        assertFalse(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.DBus.Properties.PropertiesChanged " +
                    "('org.freedesktop.NetworkManager', {'Connectivity': <uint32 2>}, @as [])"
            )
        )
        assertFalse(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/NetworkManager: org.freedesktop.NetworkManager.DeviceAdded " +
                    "(objectpath '/org/freedesktop/NetworkManager/Devices/3',)"
            )
        )
        // No cross-matching with the logind resume signal.
        assertFalse(
            isNetworkRestoredSignalLine(
                "/org/freedesktop/login1: org.freedesktop.login1.Manager.PrepareForSleep (false)"
            )
        )
    }

    @Test
    fun `offline retry follows the backoff schedule then exhausts`() {
        assertEquals(5_000L, offlineRetryDelayMs(attempt = 0, isOffline = true))
        assertEquals(15_000L, offlineRetryDelayMs(attempt = 1, isOffline = true))
        assertNull(offlineRetryDelayMs(attempt = 2, isOffline = true))
    }

    @Test
    fun `offline retry never fires for non-offline failures`() {
        assertNull(offlineRetryDelayMs(attempt = 0, isOffline = false))
        assertNull(offlineRetryDelayMs(attempt = 1, isOffline = false))
    }

    @Test
    fun `network restore kick pause is a few seconds and under the debounce`() {
        assertTrue(NETWORK_RESTORE_KICK_DELAY_MS >= 1_000L)
        // Worst-case pause (delay + max jitter) must finish before the debounce window reopens,
        // so a delayed kick can't still be pending when the next one is accepted.
        assertTrue(NETWORK_RESTORE_KICK_DELAY_MS + NETWORK_RESTORE_KICK_JITTER_MS < NETWORK_RESTORE_DEBOUNCE_MS)
    }

    @Test
    fun `resume kick pause is several seconds and under the debounce`() {
        // Long enough to sit out the post-wake thundering herd / DNS warm-up…
        assertTrue(RESUME_KICK_DELAY_MS >= 5_000L)
        // …but a worst-case pause (delay + max jitter) must finish before the debounce window
        // reopens, so a delayed kick can't still be pending when the next one is accepted.
        assertTrue(RESUME_KICK_DELAY_MS + RESUME_KICK_JITTER_MS < RESUME_DEBOUNCE_MS)
    }

    @Test
    fun `warmup grace covers the full recovery pipeline`() {
        // Resume hold-off + offline retry backoff + network-restored kick pause must all fit,
        // or the banner escalates to a hard error while recovery is still legitimately in flight.
        val worstCaseRecoveryMs = RESUME_KICK_DELAY_MS + RESUME_KICK_JITTER_MS +
            OFFLINE_RETRY_DELAYS_MS.sum() +
            NETWORK_RESTORE_KICK_DELAY_MS + NETWORK_RESTORE_KICK_JITTER_MS
        assertTrue(NETWORK_WARMUP_GRACE_MS > worstCaseRecoveryMs)
    }

    @Test
    fun `warmup window is open only shortly after a wake event`() {
        val wake = 1_000_000L
        assertTrue(isNetworkWarmupWindow(lastWakeEventMs = wake, nowMs = wake + 10_000L))
        assertTrue(isNetworkWarmupWindow(lastWakeEventMs = wake, nowMs = wake + NETWORK_WARMUP_GRACE_MS - 1))
        assertFalse(isNetworkWarmupWindow(lastWakeEventMs = wake, nowMs = wake + NETWORK_WARMUP_GRACE_MS))
        // Hours after the wake, persistent offline failures must escalate to the real error.
        assertFalse(isNetworkWarmupWindow(lastWakeEventMs = wake, nowMs = wake + 3 * 60 * 60 * 1000L))
    }

    @Test
    fun `warmup window closed with no wake event or clock skew`() {
        assertFalse(isNetworkWarmupWindow(lastWakeEventMs = null, nowMs = 1_000_000L))
        // Wake row from the "future" (wall clock stepped back): fail closed to the real banner.
        assertFalse(isNetworkWarmupWindow(lastWakeEventMs = 2_000_000L, nowMs = 1_000_000L))
    }

    @Test
    fun `offline classification by exception class name`() {
        // Ktor CIO's DNS failure — the class actually seen right after resume (null message).
        assertTrue(com.weatherwidget.data.model.isOfflineExceptionName("java.nio.channels.UnresolvedAddressException"))
        assertTrue(com.weatherwidget.data.model.isOfflineExceptionName("java.net.UnknownHostException"))
        assertFalse(com.weatherwidget.data.model.isOfflineExceptionName("kotlinx.serialization.SerializationException"))
        assertFalse(com.weatherwidget.data.model.isOfflineExceptionName(""))
    }

    @Test
    fun `offline retry schedule is positive and non-decreasing`() {
        assertTrue(OFFLINE_RETRY_DELAYS_MS.isNotEmpty())
        assertTrue(OFFLINE_RETRY_DELAYS_MS.all { it > 0 })
        assertEquals(OFFLINE_RETRY_DELAYS_MS, OFFLINE_RETRY_DELAYS_MS.sorted())
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
