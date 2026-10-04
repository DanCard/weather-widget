package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the "ask only for the missing window" table. Mutation check: always returning DEEP_MINUTES
 * (the old behaviour) makes the gap cases fail; always returning MIN_MINUTES makes the first-run
 * case fail.
 */
@Category(ShortDuration::class)
class SynopticFetchWindowTest {

    private val now = 1_760_000_000_000L

    @Test
    fun `no stored rows requests the deep window`() {
        assertEquals(SynopticFetchWindow.DEEP_MINUTES, SynopticFetchWindow.recentMinutes(null, now))
    }

    @Test
    fun `a gap past 24 hours requests the deep window`() {
        val newest = now - SynopticFetchWindow.GAP_MS
        assertEquals(SynopticFetchWindow.DEEP_MINUTES, SynopticFetchWindow.recentMinutes(newest, now))
    }

    @Test
    fun `just-stored rows still request the minimum window`() {
        // 5 minutes ago: 5 + 30 margin = 35, clamped up to 120 so daily extremes keep context.
        val newest = now - 5 * 60_000L
        assertEquals(SynopticFetchWindow.MIN_MINUTES, SynopticFetchWindow.recentMinutes(newest, now))
    }

    @Test
    fun `the window is the gap plus a 30 minute margin`() {
        // 3 hours ago: 180 + 30 = 210.
        val newest = now - 3 * 60 * 60_000L
        assertEquals(210, SynopticFetchWindow.recentMinutes(newest, now))
    }

    @Test
    fun `the window never exceeds the deep maximum`() {
        // 23 hours ago: 1380 + 30 = 1410, still under 1440.
        val almostDay = now - 23 * 60 * 60_000L
        assertEquals(1410, SynopticFetchWindow.recentMinutes(almostDay, now))

        // 23 hours 50 minutes ago: 1430 + 30 = 1460, clamped to 1440.
        val edge = now - (23 * 60 + 50) * 60_000L
        assertEquals(SynopticFetchWindow.DEEP_MINUTES, SynopticFetchWindow.recentMinutes(edge, now))
    }

    @Test
    fun `exactly 24 hours is the deep window boundary`() {
        val exactlyDay = now - SynopticFetchWindow.GAP_MS
        assertEquals(SynopticFetchWindow.DEEP_MINUTES, SynopticFetchWindow.recentMinutes(exactlyDay, now))
        val justUnder = now - SynopticFetchWindow.GAP_MS + 60_000L
        assertEquals(SynopticFetchWindow.DEEP_MINUTES, SynopticFetchWindow.recentMinutes(justUnder, now))
    }
}
