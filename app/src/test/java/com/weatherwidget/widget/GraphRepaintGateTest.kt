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

    /**
     * A watermark that did not move between renders — "same observations, later clock".
     * Supplied explicitly because a *null* last-watermark is not neutral: it means the render
     * predates watermark tracking and must force one rebuild. See `watermark absent` below.
     */
    private val STEADY_WATERMARK = 1_787_096_100_000L

    @Test
    fun `no prior render forces rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = null,
            currentDisplayedTemp = "72°",
            lastRenderMs = 0L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
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
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
        )
        assertTrue(decision.shouldRebuild)
        assertTrue(decision.reason.startsWith("now_drift="))
    }

    // --- Data watermark (plans/260818-widget-repaint-gate-data-watermark.md) ---

    /**
     * The bug this whole change exists for: a new KNUQ reading landed, but the blended display temp
     * still formatted to the same string, so the old gate returned `header_only_live` and left a
     * stale `knuq 71.6 @ 4:35` on the graph. The station label lives in the bitmap, not the header.
     */
    @Test
    fun `new observation with unchanged temp string forces rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "73.1°",
            currentDisplayedTemp = "73.1°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK + 15 * 60_000L,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("data_changed", decision.reason)
    }

    /**
     * The battery guard, and the reason the watermark keys on observation `timestamp` rather than
     * `fetchedAt`: a repaint pass with no new reading must still fall through to the cheap header
     * update. If this fails, every ~2-min tick pays the ~800 ms bitmap rebuild.
     */
    @Test
    fun `unchanged watermark still skips rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
        )
        assertFalse(decision.shouldRebuild)
        assertEquals("header_only_live", decision.reason)
    }

    /** Upgrade path: prefs hold a render from before watermark tracking. Rebuild once, don't assume. */
    @Test
    fun `watermark absent forces one rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = null,
            currentWatermarkMs = STEADY_WATERMARK,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("watermark_absent", decision.reason)
    }

    /**
     * No observations to measure is not the same as "data changed". NONE must not read as a
     * regression *or* an advance — otherwise a location with no rows rebuilds on every pass.
     */
    @Test
    fun `no observations does not force rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = ObservationWatermark.NONE,
        )
        assertFalse(decision.shouldRebuild)
        assertEquals("header_only_live", decision.reason)
    }

    /** Retention cleanup or a narrowed scope can drop the newest row. Going backwards is not new data. */
    @Test
    fun `watermark regression does not force rebuild`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK - 60 * 60_000L,
        )
        assertFalse(decision.shouldRebuild)
        assertEquals("header_only_live", decision.reason)
    }

    /** Both signals firing must resolve deterministically; data_changed is the more specific answer. */
    @Test
    fun `data changed outranks temp changed`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "75°",
            lastRenderMs = nowMs - 60_000L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK + 60_000L,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("data_changed", decision.reason)
    }

    /**
     * A screen-off skip means no render happened to compare against, so every other signal is
     * measuring against the wrong baseline. It has to win outright — including over the
     * no-prior-render check, whose reason would otherwise mask why the rebuild happened.
     */
    @Test
    fun `paint owed outranks every other signal`() {
        val decision = GraphRepaintGate.shouldRebuildBitmap(
            displayedTemp = "72°",
            currentDisplayedTemp = "72°",
            lastRenderMs = 0L,
            nowMs = nowMs,
            windowSpanMinutes = windowSpanMinutes,
            bitmapWidthPx = bitmapWidthPx,
            lastWatermarkMs = STEADY_WATERMARK,
            currentWatermarkMs = STEADY_WATERMARK,
            paintOwed = true,
        )
        assertTrue(decision.shouldRebuild)
        assertEquals("paint_owed", decision.reason)
    }
}
