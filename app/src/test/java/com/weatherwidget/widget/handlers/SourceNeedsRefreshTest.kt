package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.SourceToggleRefreshPolicy
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
    private val batteryStaleMs = SourceToggleRefreshPolicy.staleMs(isHighBattery = false)

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
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(oneMinuteAgo), now, isHighBattery = false))
    }

    @Test
    fun justUnderStaleThreshold_doesNotRefresh() {
        val justUnder = now - batteryStaleMs + 60_000L
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(justUnder), now, isHighBattery = false))
    }

    @Test
    fun atStaleThreshold_refreshes() {
        val exactlyAtThreshold = now - batteryStaleMs
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(complete(exactlyAtThreshold), now, isHighBattery = false))
    }

    /** 2026-11-01: on a charger the forecast is due every 2 h, so a toggle at 3 h must fetch. */
    @Test
    fun threeHoursOld_refreshesOnChargerButNotOnBattery() {
        val threeHoursAgo = complete(now - 3 * 60 * 60 * 1000L)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(threeHoursAgo, now, isHighBattery = true))
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(threeHoursAgo, now, isHighBattery = false))
    }

    /** 2026-10-08: toggling back to a source fetched 40 minutes ago refetched Google (3 pages). */
    @Test
    fun fortyMinutesOld_doesNotRefreshOnToggle() {
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(now - 40 * 60_000L), now, isHighBattery = false))
    }

    @Test
    fun pastStaleThreshold_refreshes() {
        val nineHoursAgo = now - 9 * 60 * 60 * 1000L
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(complete(nineHoursAgo), now, isHighBattery = false))
    }

    @Test
    fun unchangedSuccessfulFetch_advancesCooldownWithoutRewrittenRows() {
        val oldContentTimestamp = now - 9 * 60 * 60 * 1000L
        val recentSuccessfulFetch = now - 60_000L
        val state = complete(oldContentTimestamp).copy(lastSuccessfulFetchAtMs = recentSuccessfulFetch)

        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(state, now, isHighBattery = false))
    }

    @Test
    fun staleSuccessfulFetch_doesNotHideOldRows() {
        val oldContentTimestamp = now - 9 * 60 * 60 * 1000L
        val oldSuccessfulFetch = now - 2 * batteryStaleMs
        val state = complete(oldContentTimestamp).copy(lastSuccessfulFetchAtMs = oldSuccessfulFetch)

        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now, isHighBattery = false))
    }

    @Test
    fun missingDaily_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasDaily = false)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now, isHighBattery = false))
    }

    @Test
    fun missingHourly_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasHourly = false)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now, isHighBattery = false))
    }

    @Test
    fun insufficientFutureCoverage_refreshesEvenWhenFresh() {
        val state = complete(now).copy(hasRequiredFutureCoverage = false)
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now, isHighBattery = false))
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
        assertTrue(SourceStalenessProbe.sourceNeedsRefresh(state, now, isHighBattery = false))
    }

    /**
     * Defensive: a fetchedAt in the future (clock skew) must not be read as "stale" via a negative
     * age, nor loop — it simply reads as fresh.
     */
    @Test
    fun futureFetchedAt_doesNotRefresh() {
        assertFalse(SourceStalenessProbe.sourceNeedsRefresh(complete(now + 60_000L), now, isHighBattery = false))
    }
}
