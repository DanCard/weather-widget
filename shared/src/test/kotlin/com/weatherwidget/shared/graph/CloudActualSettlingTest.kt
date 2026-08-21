package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@Category(ShortDuration::class)
class CloudActualSettlingTest {

    private val hour = 3_600_000L
    private val now = 1_755_720_000_000L

    /** An hour is an actual as soon as it has ended. */
    @Test
    fun `an hour that has ended is settled`() {
        val target = now - hour // ends exactly at `now`

        assertTrue(CloudSeriesBuilder.isRetroCorrected(target, now))
        assertTrue(CloudActualSettling.hasSettled(target, now))
    }

    @Test
    fun `the boundary is the hour end plus the settling lag`() {
        val target = now - hour
        val settledAt = target + hour + CloudActualSettling.SETTLE_MS

        assertFalse(CloudActualSettling.hasSettled(target, settledAt - 1))
        assertTrue(CloudActualSettling.hasSettled(target, settledAt))
    }

    /**
     * The constraint that killed the 2-hour lag: an hour qualifies only when
     * `hourStart <= now - 1h - SETTLE_MS`, so a lag larger than the visible past span minus two
     * hours leaves fewer than the two points the renderer needs, and the actual curve cannot be
     * drawn at all. The narrowest supported zoom shows ~3h of past.
     *
     * Measured 2026-08-20 22:54 on a 20:00-00:00 window: 0h -> 2 settled hours, 1h -> 1, 2h -> 0.
     */
    @Test
    fun `the lag leaves at least two settled hours in the narrowest zoom`() {
        val narrowestPastSpanMs = 3 * hour
        val windowStart = now - narrowestPastSpanMs
        val settledInWindow = generateSequence(windowStart) { it + hour }
            .takeWhile { it < now }
            .count { CloudActualSettling.hasSettled(it, now) }

        assertTrue(
            "SETTLE_MS=${CloudActualSettling.SETTLE_MS} leaves only $settledInWindow settled " +
                "hour(s) in the narrowest window; the renderer needs 2",
            settledInWindow >= 2,
        )
    }

    @Test
    fun `the in-progress hour and the future never settle`() {
        assertFalse("in progress", CloudActualSettling.hasSettled(now, now + 30 * 60_000L))
        assertFalse("future", CloudActualSettling.hasSettled(now + 4 * hour, now))
    }

    /** Sub-hourly steps go through the same predicate; nothing about it is hour-aligned. */
    @Test
    fun `quarter-hour steps settle independently`() {
        val q = 15 * 60_000L
        val base = now - 5 * hour
        listOf(0L, q, 2 * q, 3 * q).forEach {
            assertTrue(
                "step at +$it should have settled",
                CloudActualSettling.hasSettled(base + it, now),
            )
        }
        assertFalse(
            "a step whose hour has not ended must not",
            CloudActualSettling.hasSettled(now - q, now),
        )
    }
}
