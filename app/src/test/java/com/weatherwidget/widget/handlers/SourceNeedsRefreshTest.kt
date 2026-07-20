package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Policy tests for the API-toggle refresh gate. Pure function, no database — the DB reads live in
 * sourceWindowState() and are covered by the Robolectric toggle integration test.
 */
@Category(ShortDuration::class)
class SourceNeedsRefreshTest {
    private val now = 1_800_000_000_000L

    private fun complete(fetchedAtMs: Long?) =
        WidgetIntentRouter.SourceWindowState(
            hasDaily = true,
            hasHourly = true,
            hasRequiredFutureCoverage = true,
            newestFetchedAtMs = fetchedAtMs,
        )

    @Test
    fun freshAndComplete_doesNotRefresh() {
        val oneMinuteAgo = now - 60_000L
        assertFalse(WidgetIntentRouter.sourceNeedsRefresh(complete(oneMinuteAgo), now))
    }

    @Test
    fun justUnderStaleThreshold_doesNotRefresh() {
        val fourteenMinutesAgo = now - WidgetIntentRouter.TOGGLE_REFRESH_STALE_MS + 60_000L
        assertFalse(WidgetIntentRouter.sourceNeedsRefresh(complete(fourteenMinutesAgo), now))
    }

    @Test
    fun atStaleThreshold_refreshes() {
        val exactlyFifteenMinutesAgo = now - WidgetIntentRouter.TOGGLE_REFRESH_STALE_MS
        assertTrue(WidgetIntentRouter.sourceNeedsRefresh(complete(exactlyFifteenMinutesAgo), now))
    }

    @Test
    fun pastStaleThreshold_refreshes() {
        val nineHoursAgo = now - 9 * 60 * 60 * 1000L
        assertTrue(WidgetIntentRouter.sourceNeedsRefresh(complete(nineHoursAgo), now))
    }

    @Test
    fun missingDaily_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasDaily = false)
        assertTrue(WidgetIntentRouter.sourceNeedsRefresh(state, now))
    }

    @Test
    fun missingHourly_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasHourly = false)
        assertTrue(WidgetIntentRouter.sourceNeedsRefresh(state, now))
    }

    @Test
    fun insufficientFutureCoverage_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasRequiredFutureCoverage = false)
        assertTrue(WidgetIntentRouter.sourceNeedsRefresh(state, now))
    }

    @Test
    fun noRowsAtAll_refreshes() {
        val state =
            WidgetIntentRouter.SourceWindowState(
                hasDaily = false,
                hasHourly = false,
                hasRequiredFutureCoverage = false,
                newestFetchedAtMs = null,
            )
        assertTrue(WidgetIntentRouter.sourceNeedsRefresh(state, now))
    }

    /**
     * Defensive: a fetchedAt in the future (clock skew) must not be read as "stale" via a negative
     * age, nor loop — it simply reads as fresh.
     */
    @Test
    fun futureFetchedAt_doesNotRefresh() {
        assertFalse(WidgetIntentRouter.sourceNeedsRefresh(complete(now + 60_000L), now))
    }
}
