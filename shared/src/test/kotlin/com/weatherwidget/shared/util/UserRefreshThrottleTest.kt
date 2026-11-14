package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class UserRefreshThrottleTest {

    @Test
    fun `null or non-positive lastRefresh allows refresh`() {
        assertEquals(UserRefreshThrottle.Decision.Allowed, UserRefreshThrottle.check(null, 1_000_000L, lastFetchMs = null))
        assertEquals(UserRefreshThrottle.Decision.Allowed, UserRefreshThrottle.check(0L, 1_000_000L, lastFetchMs = null))
        assertEquals(UserRefreshThrottle.Decision.Allowed, UserRefreshThrottle.check(-1L, 1_000_000L, lastFetchMs = null))
    }

    @Test
    fun `time going backwards allows refresh`() {
        assertEquals(UserRefreshThrottle.Decision.Allowed, UserRefreshThrottle.check(2_000_000L, 1_000_000L, lastFetchMs = null))
    }

    @Test
    fun `refresh at or beyond 10 minutes allows refresh`() {
        val lastRefresh = 1_000_000L
        val exactly10Min = lastRefresh + 10 * 60_000L
        assertEquals(UserRefreshThrottle.Decision.Allowed, UserRefreshThrottle.check(lastRefresh, exactly10Min, lastFetchMs = null))

        val after11Min = lastRefresh + 11 * 60_000L
        assertEquals(UserRefreshThrottle.Decision.Allowed, UserRefreshThrottle.check(lastRefresh, after11Min, lastFetchMs = null))
    }

    @Test
    fun `refresh within 10 minutes throttles with correct elapsed and remaining minutes`() {
        val lastRefresh = 1_000_000L

        // 30 seconds elapsed -> 0 min elapsed, 10 min remaining
        val at30Sec = lastRefresh + 30_000L
        assertEquals(
            UserRefreshThrottle.Decision.Throttled(elapsedMinutes = 0, remainingMinutes = 10),
            UserRefreshThrottle.check(lastRefresh, at30Sec, lastFetchMs = null),
        )

        // 1 minute elapsed -> 1 min elapsed, 9 min remaining
        val at1Min = lastRefresh + 60_000L
        assertEquals(
            UserRefreshThrottle.Decision.Throttled(elapsedMinutes = 1, remainingMinutes = 9),
            UserRefreshThrottle.check(lastRefresh, at1Min, lastFetchMs = null),
        )

        // 3 minutes 20 seconds elapsed -> 3 min elapsed, 7 min remaining
        val at3Min20Sec = lastRefresh + 200_000L
        assertEquals(
            UserRefreshThrottle.Decision.Throttled(elapsedMinutes = 3, remainingMinutes = 7),
            UserRefreshThrottle.check(lastRefresh, at3Min20Sec, lastFetchMs = null),
        )

        // 9 minutes 59 seconds elapsed -> 9 min elapsed, 1 min remaining
        val at9Min59Sec = lastRefresh + 599_000L
        assertEquals(
            UserRefreshThrottle.Decision.Throttled(elapsedMinutes = 9, remainingMinutes = 1),
            UserRefreshThrottle.check(lastRefresh, at9Min59Sec, lastFetchMs = null),
        )
    }

    @Test
    fun `formatMessage handles singular and plural minutes`() {
        assertEquals(
            "Not doing because last refresh was 0 minutes ago, and wait another 10 minutes before asking for a refresh",
            UserRefreshThrottle.formatMessage(0, 10),
        )
        assertEquals(
            "Not doing because last refresh was 1 minute ago, and wait another 9 minutes before asking for a refresh",
            UserRefreshThrottle.formatMessage(1, 9),
        )
        assertEquals(
            "Not doing because last refresh was 9 minutes ago, and wait another 1 minute before asking for a refresh",
            UserRefreshThrottle.formatMessage(9, 1),
        )
        assertEquals(
            "Not doing because last refresh was 3 minutes ago, and wait another 7 minutes before asking for a refresh",
            UserRefreshThrottle.formatMessage(3, 7),
        )
    }

    @Test
    fun `an automatic fetch within 10 minutes throttles a user refresh`() {
        // User, 2026-11-13: popup fetched NWS 74 s before the refresh; "once every 10 minutes max".
        val now = 1_000_000_000L
        val decision = UserRefreshThrottle.check(lastRefreshMs = null, nowMs = now, lastFetchMs = now - 74_000L)
        println("fetched 74 s ago, no user refresh: $decision")
        assertEquals(UserRefreshThrottle.Decision.Throttled(elapsedMinutes = 1, remainingMinutes = 9), decision)
    }

    @Test
    fun `the later of user refresh and fetch decides`() {
        val now = 1_000_000_000L
        assertEquals(
            UserRefreshThrottle.Decision.Throttled(elapsedMinutes = 2, remainingMinutes = 8),
            UserRefreshThrottle.check(lastRefreshMs = now - 20 * 60_000L, nowMs = now, lastFetchMs = now - 2 * 60_000L),
        )
        assertEquals(
            "both older than 10 minutes",
            UserRefreshThrottle.Decision.Allowed,
            UserRefreshThrottle.check(lastRefreshMs = now - 20 * 60_000L, nowMs = now, lastFetchMs = now - 10 * 60_000L),
        )
        assertEquals(
            "a future fetch time (clock change) is ignored",
            UserRefreshThrottle.Decision.Allowed,
            UserRefreshThrottle.check(lastRefreshMs = null, nowMs = now, lastFetchMs = now + 60_000L),
        )
    }
}
