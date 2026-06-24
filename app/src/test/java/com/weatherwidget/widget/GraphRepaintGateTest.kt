package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class GraphRepaintGateTest {

    private val nowMs = 1_000_000L
    private val windowSpanMinutes = 720L // 12h WIDE
    private val bitmapWidthPx = 800

    @Test
    fun `no prior render forces rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = null,
            currentDisplayedTemp = "72°",
            lastRenderMs = 0L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("no_prior_render", decision.reason)
    }

    @Test
    fun `temp changed forces rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "73°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("temp_changed", decision.reason)
    }

    @Test
    fun `same temp within drift budget skips rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 60_000L, // 1 min ago
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertFalse(decision.shouldRebuild)
        assertEquals("header_only_live", decision.reason)
    }

    @Test
    fun `now drift above budget forces rebuild`() {
        // 800px / 720min = 1.11 px/min; 5 min → 5.56 px > 4 budget
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 5 * 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertTrue(decision.shouldRebuild)
        assertTrue(decision.reason.startsWith("now_drift="))
    }

    @Test
    fun `now drift just below budget skips rebuild`() {
        // 800px / 720min = 1.11 px/min; 3 min → 3.33 px < 4 budget
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 3 * 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertFalse(decision.shouldRebuild)
        assertEquals("header_only_live", decision.reason)
    }

    @Test
    fun `max interval forces rebuild even when temp unchanged`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - GraphRepaintGate.MAX_BITMAP_INTERVAL_MS,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("max_interval", decision.reason)
    }

    @Test
    fun `just below max interval with no drift skips rebuild`() {
        // 1 min ago, well under both drift and max interval
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertFalse(decision.shouldRebuild)
    }

    @Test
    fun `zero window span skips drift check`() {
        // Degenerate window: drift check is skipped, only temp and max-interval matter
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 10 * 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = 0L,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertFalse(decision.shouldRebuild)
    }

    @Test
    fun `narrow zoom tightens drift budget`() {
        // NARROW: 4h = 240min; 800px / 240min = 3.33 px/min; 2 min → 6.67 px > 4
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 2 * 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = 240L,
            bitmapWidthPx = bitmapWidthPx,
        )
        assertTrue(decision.shouldRebuild)
        assertTrue(decision.reason.startsWith("now_drift="))
    }
}
