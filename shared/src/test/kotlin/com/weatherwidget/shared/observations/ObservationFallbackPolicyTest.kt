package com.weatherwidget.shared.observations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
