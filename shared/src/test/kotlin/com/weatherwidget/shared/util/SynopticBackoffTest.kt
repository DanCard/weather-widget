package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SynopticBackoffTest {

    @Test
    fun `no failures produce no backoff`() {
        assertEquals(0L, SynopticBackoff.backoffFor(0))
        assertEquals(0L, SynopticBackoff.backoffFor(-1))
    }

    @Test
    fun `first failure waits the base interval`() {
        assertEquals(SynopticBackoff.BASE_BACKOFF_MS, SynopticBackoff.backoffFor(1))
    }

    @Test
    fun `backoff doubles per consecutive failure`() {
        assertEquals(SynopticBackoff.BASE_BACKOFF_MS * 2, SynopticBackoff.backoffFor(2))
        assertEquals(SynopticBackoff.BASE_BACKOFF_MS * 4, SynopticBackoff.backoffFor(3))
    }

    @Test
    fun `backoff caps at the max interval`() {
        assertEquals(SynopticBackoff.MAX_BACKOFF_MS, SynopticBackoff.backoffFor(8))
        assertEquals(SynopticBackoff.MAX_BACKOFF_MS, SynopticBackoff.backoffFor(50))
    }

    @Test
    fun `a 17 hour outage stays under twenty attempts`() {
        // The observed 2026-09-08 outage: ~17h of rejections. Exponential backoff should probe a
        // handful of times, not once per hour.
        var t = 0L
        var attempts = 0
        var streak = 0
        while (t < 17 * 60 * 60 * 1000L) {
            streak++
            t += SynopticBackoff.backoffFor(streak)
            attempts++
        }
        assertTrue("expected few attempts, got $attempts", attempts <= 8)
    }

    @Test
    fun `an active backoff skips ordinary fetches`() {
        assertTrue(SynopticBackoff.shouldSkip(nowMs = 1_000L, backoffUntilMs = 2_000L, userLocationChange = false))
    }

    @Test
    fun `a user location change fetches through an active backoff`() {
        // 2026-09-28: a Kyiv timeout at 09:13 blanked Warsaw's yesterday after a 09:14 move.
        assertFalse(SynopticBackoff.shouldSkip(nowMs = 1_000L, backoffUntilMs = 2_000L, userLocationChange = true))
    }

    @Test
    fun `an expired backoff skips nothing`() {
        assertFalse(SynopticBackoff.shouldSkip(nowMs = 3_000L, backoffUntilMs = 2_000L, userLocationChange = false))
    }
}
