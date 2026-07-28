package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Policy tests for the API-toggle refresh gate. Pure function, no database — the DB reads live in
 * [SourceStalenessProbe.sourceWindowState] and are covered by the Robolectric toggle integration
 * test. Migrated from `WidgetIntentRouter.*` references when the staleness policy was extracted
 * into [SourceStalenessProbe] (third-pass review N7).
 */
@Category(ShortDuration::class)
class SourceNeedsRefreshTest {
    private val now = 1_800_000_000_000L

    private fun complete(fetchedAtMs: Long?) =
        SourceStalenessProbe.SourceWindowState(
            hasDaily = true,
            hasHourly = true,
            hasRequiredFutureCoverage = true,
            newestFetchedAtMs = fetchedAtMs,
        )

    @Test
    fun freshAndComplete_doesNotRefresh() {
        val oneMinuteAgo = now - 60_000L
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(oneMinuteAgo), now))
    }

    @Test
    fun justUnderStaleThreshold_doesNotRefresh() {
        val fourteenMinutesAgo = now - SourceStalenessProbe.TOGGLE_REFRESH_STALE_MS + 60_000L
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(fourteenMinutesAgo), now))
    }

    @Test
    fun atStaleThreshold_refreshes() {
        val exactlyFifteenMinutesAgo = now - SourceStalenessProbe.TOGGLE_REFRESH_STALE_MS
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(complete(exactlyFifteenMinutesAgo), now))
    }

    @Test
    fun pastStaleThreshold_refreshes() {
        val nineHoursAgo = now - 9 * 60 * 60 * 1000L
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(complete(nineHoursAgo), now))
    }

    @Test
    fun unchangedSuccessfulFetch_advancesCooldownWithoutRewrittenRows() {
        val oldContentTimestamp = now - 9 * 60 * 60 * 1000L
        val recentSuccessfulFetch = now - 60_000L
        val state = complete(oldContentTimestamp).copy(lastSuccessfulFetchAtMs = recentSuccessfulFetch)

        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(state, now))
    }

    @Test
    fun staleSuccessfulFetch_doesNotHideOldRows() {
        val oldContentTimestamp = now - 9 * 60 * 60 * 1000L
        val oldSuccessfulFetch = now - 2 * SourceStalenessProbe.TOGGLE_REFRESH_STALE_MS
        val state = complete(oldContentTimestamp).copy(lastSuccessfulFetchAtMs = oldSuccessfulFetch)

        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now))
    }

    @Test
    fun missingDaily_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasDaily = false)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now))
    }

    @Test
    fun missingHourly_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasHourly = false)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now))
    }

    @Test
    fun insufficientFutureCoverage_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasRequiredFutureCoverage = false)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now))
    }

    @Test
    fun noRowsAtAll_refreshes() {
        val state =
            SourceStalenessProbe.SourceWindowState(
                hasDaily = false,
                hasHourly = false,
                hasRequiredFutureCoverage = false,
                newestFetchedAtMs = null,
            )
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now))
    }

    /**
     * Defensive: a fetchedAt in the future (clock skew) must not be read as "stale" via a negative
     * age, nor loop — it simply reads as fresh.
     */
    @Test
    fun futureFetchedAt_doesNotRefresh() {
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(now + 60_000L), now))
    }
}
