package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class StaleDisplayRefreshPolicyTest {

    private val now = 1_800_000_000_000L
    private val minute = 60 * 1000L

    @Test
    fun `fresh fetched and fresh reported rows do not fire`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = now - 5 * minute,
            newestReportedMs = now - 6 * minute,
            lastTriggerMs = 0L,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.SKIP_FRESH, decision)
    }

    @Test
    fun `fetch past the staleness threshold fires`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = now - StaleDisplayRefreshPolicy.STALE_FETCH_THRESHOLD_MS,
            newestReportedMs = now - 2 * minute,
            lastTriggerMs = 0L,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.FIRE, decision)
    }

    // The 2026-08-21 incident: fresh rows existed in the DB but under a neighbouring location
    // fragment, so what the screen read was 78 minutes old while plugged in. A fetch this old must
    // fire even though the stations themselves were reporting normally.
    @Test
    fun `an hour-old fetch fires regardless of reported age`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = now - 78 * minute,
            newestReportedMs = now - 80 * minute,
            lastTriggerMs = 0L,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.FIRE, decision)
    }

    @Test
    fun `recent fetch with very old reports is quiet stations, not a fetch trigger`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = now - 10 * minute,
            newestReportedMs = now - StaleDisplayRefreshPolicy.QUIET_STATIONS_LAG_MS,
            lastTriggerMs = 0L,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.QUIET_STATIONS, decision)
    }

    @Test
    fun `a trigger inside the debounce window wins over every data condition`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = now - 78 * minute,
            newestReportedMs = now - 80 * minute,
            lastTriggerMs = now - StaleDisplayRefreshPolicy.TRIGGER_DEBOUNCE_MS + 1L,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.RECENT_TRIGGER, decision)
    }

    @Test
    fun `triggering again at the debounce boundary is allowed`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = now - 78 * minute,
            newestReportedMs = now - 80 * minute,
            lastTriggerMs = now - StaleDisplayRefreshPolicy.TRIGGER_DEBOUNCE_MS,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.FIRE, decision)
    }

    @Test
    fun `no displayed rows means nothing to judge`() {
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = now,
            newestFetchedMs = null,
            newestReportedMs = null,
            lastTriggerMs = 0L,
        )
        assertEquals(StaleDisplayRefreshPolicy.Decision.NO_ROWS, decision)
    }
}
