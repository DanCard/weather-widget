package com.weatherwidget.shared.observations

import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationFallbackPolicyTest {

    private val now = 1_768_000_000_000L
    private val twoHoursAgo = now - 2 * 60 * 60 * 1000L
    private val tenMinutesAgo = now - 10 * 60 * 1000L

    @Test
    fun `station at index 2 gets the web fallback`() {
        // The regression that started this: KPAO sits at index 2 in the distance-sorted station
        // list. Desktop's old hardcoded `index < 2` excluded it, so its stale API reading was never
        // replaced by the web source while Android's was.
        assertTrue(ObservationFallbackPolicy.shouldUseWebFallback(2, twoHoursAgo, now))
    }

    @Test
    fun `fallback is limited to the nearest three stations`() {
        assertTrue(ObservationFallbackPolicy.shouldUseWebFallback(0, twoHoursAgo, now))
        assertTrue(ObservationFallbackPolicy.shouldUseWebFallback(1, twoHoursAgo, now))
        assertTrue(ObservationFallbackPolicy.shouldUseWebFallback(2, twoHoursAgo, now))
        assertFalse(ObservationFallbackPolicy.shouldUseWebFallback(3, twoHoursAgo, now))
        assertFalse(ObservationFallbackPolicy.shouldUseWebFallback(4, twoHoursAgo, now))
    }

    @Test
    fun `fresh observation does not trigger the fallback`() {
        assertFalse(ObservationFallbackPolicy.shouldUseWebFallback(0, tenMinutesAgo, now))
        assertFalse(ObservationFallbackPolicy.isStale(tenMinutesAgo, now))
    }

    @Test
    fun `missing observation is stale`() {
        assertTrue(ObservationFallbackPolicy.isStale(null, now))
        assertTrue(ObservationFallbackPolicy.shouldUseWebFallback(0, null, now))
    }

    @Test
    fun `staleness boundary is one hour`() {
        val exactlyOneHourAgo = now - ObservationFallbackPolicy.STALE_AFTER_MS
        assertFalse("exactly 1h old is not yet stale", ObservationFallbackPolicy.isStale(exactlyOneHourAgo, now))
        assertTrue("a millisecond older is stale", ObservationFallbackPolicy.isStale(exactlyOneHourAgo - 1, now))
    }

    @Test
    fun `fallback reason distinguishes a silent station from a lagging one`() {
        assertEquals("empty", ObservationFallbackPolicy.fallbackReason(0))
        assertEquals("stale", ObservationFallbackPolicy.fallbackReason(12))
    }

    /**
     * The regression this window exists to prevent (KPAO 2026-07-13): the fallback fires only once a
     * station is >1h stale, so a flat 60-minute request could never contain the reading it was sent
     * to re-check. Synoptic answered "no stations found", the QC flag on a bogus 50°F reading was
     * never seen, and the unflagged value stayed in the blend. Whenever the fallback fires, the
     * window MUST reach past the station's newest reading.
     */
    @Test
    fun `window always reaches the reading that triggered the fallback`() {
        // Up to the derived worst case (longest fetch gap + blend neighbourhood). Beyond that the
        // window caps out, which is accepted: a station silent for longer than the cap was already
        // fetched — and QC-checked — while its readings were fresh.
        for (staleMinutes in longArrayOf(61, 90, 192, BatteryTier.INTERVAL_MEDIUM_MINUTES, 660)) {
            val newest = now - staleMinutes * 60_000L
            assertTrue(
                "fallback should fire at $staleMinutes min stale",
                ObservationFallbackPolicy.shouldUseWebFallback(0, newest, now),
            )
            val window = ObservationFallbackPolicy.webFallbackWindowMinutes(newest, now)
            assertTrue(
                "window ${window}min must cover a station silent for ${staleMinutes}min",
                window > staleMinutes,
            )
        }
    }

    /**
     * The cap is derived, not chosen: it must survive the worst case where a station goes silent
     * immediately after a fetch and the phone is on its longest scheduled interval. If someone later
     * trims the cap for payload reasons, this fails rather than silently re-creating the original
     * bug (a window clamped below the reading's own age returns nothing, so the QC flag is never
     * seen and the bad reading can never be healed).
     *
     * 3h would miss KPAO itself (192 min stale, 2026-07-13); 6h fails the 8h low-battery interval.
     */
    @Test
    fun `window cap covers the longest fetch gap plus the blend neighbourhood`() {
        val worstCaseSilenceMinutes =
            BatteryTier.INTERVAL_MEDIUM_MINUTES + ObservationOrigin.BLEND_MAX_AGE_MS / 60_000L

        assertTrue(
            "cap ${ObservationFallbackPolicy.MAX_WEB_FALLBACK_WINDOW_MINUTES}min must exceed the " +
                "worst-case ${worstCaseSilenceMinutes}min a reading can be unseen and still matter",
            ObservationFallbackPolicy.MAX_WEB_FALLBACK_WINDOW_MINUTES > worstCaseSilenceMinutes,
        )

        // The end-to-end property: a station silent for that worst case is still fully reachable.
        val newest = now - worstCaseSilenceMinutes * 60_000L
        assertTrue(ObservationFallbackPolicy.shouldUseWebFallback(0, newest, now))
        assertTrue(
            "window must reach a reading ${worstCaseSilenceMinutes}min old",
            ObservationFallbackPolicy.webFallbackWindowMinutes(newest, now) > worstCaseSilenceMinutes,
        )
    }

    // --- Fetch-first policy (plan 260721) ------------------------------------------------------

    @Test
    fun `fetch-both is unconditional for the nearest three stations`() {
        // No staleness argument: the top WEB_FETCH_STATIONS fetch web every cycle, fresh or not.
        assertTrue(ObservationFallbackPolicy.shouldFetchWeb(0))
        assertTrue(ObservationFallbackPolicy.shouldFetchWeb(1))
        assertTrue(ObservationFallbackPolicy.shouldFetchWeb(2))
        assertFalse(ObservationFallbackPolicy.shouldFetchWeb(3))
        assertFalse(ObservationFallbackPolicy.shouldFetchWeb(4))
    }

    @Test
    fun `metrics tier covers the nearest five stations`() {
        for (index in 0..4) assertTrue("index $index", ObservationFallbackPolicy.shouldLogWebMetrics(index))
        assertFalse(ObservationFallbackPolicy.shouldLogWebMetrics(5))
    }

    @Test
    fun `metrics tier is a superset of the fetch-both tier`() {
        // Every station that fetches-for-use also logs metrics; the reverse is not true (4 and 5 are
        // metrics-only). This coupling is what lets stations 4-5 be fetched without being displayed.
        for (index in 0..6) {
            if (ObservationFallbackPolicy.shouldFetchWeb(index)) {
                assertTrue("index $index fetches but does not log", ObservationFallbackPolicy.shouldLogWebMetrics(index))
            }
        }
        assertTrue(ObservationFallbackPolicy.WEB_METRICS_STATIONS >= ObservationFallbackPolicy.WEB_FETCH_STATIONS)
    }

    @Test
    fun `metrics window spans more than one METAR cycle`() {
        // A metrics-only fetch that asked for <=60 min could miss a late/skipped ob and log nothing —
        // the same empty-window trap the fallback window was fixed for.
        assertTrue(
            "metrics window ${ObservationFallbackPolicy.METRICS_WINDOW_MINUTES}min must exceed one hour",
            ObservationFallbackPolicy.METRICS_WINDOW_MINUTES > 60L,
        )
    }

    @Test
    fun `master switch disables both tiers`() {
        // Documents the revert lever. If FETCH_BOTH_ENABLED is ever flipped false, neither tier fires.
        if (!ObservationFallbackPolicy.FETCH_BOTH_ENABLED) {
            assertFalse(ObservationFallbackPolicy.shouldFetchWeb(0))
            assertFalse(ObservationFallbackPolicy.shouldLogWebMetrics(0))
        }
    }

    @Test
    fun `window is capped and never shrinks below the margin`() {
        val ancient = now - 40L * 60 * 60 * 1000
        assertEquals(
            ObservationFallbackPolicy.MAX_WEB_FALLBACK_WINDOW_MINUTES,
            ObservationFallbackPolicy.webFallbackWindowMinutes(ancient, now),
        )
        // A station with no known reading: we cannot tell how far back its data starts.
        assertEquals(
            ObservationFallbackPolicy.MAX_WEB_FALLBACK_WINDOW_MINUTES,
            ObservationFallbackPolicy.webFallbackWindowMinutes(null, now),
        )
        // Fresh reading (fallback would not even fire) still yields a usable, positive window.
        assertTrue(ObservationFallbackPolicy.webFallbackWindowMinutes(now, now) >= 60L)
    }
}
