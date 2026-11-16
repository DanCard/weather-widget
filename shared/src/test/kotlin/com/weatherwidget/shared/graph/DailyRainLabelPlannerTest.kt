package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyRainLabelPlannerTest {

    @Test
    fun `resolveDayRainAnchorTop prefers topmost element in screen coordinates`() {
        // When snapshot bar reaches higher (smaller Y, e.g. 20 vs 45)
        val anchor1 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = 45f,
            snapshotBarTop = 20f,
        )
        assertEquals(20f, anchor1!!, 0.001f)

        // When headline high reaches higher (e.g. 15 vs 30)
        val anchor2 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = 15f,
            snapshotBarTop = 30f,
        )
        assertEquals(15f, anchor2!!, 0.001f)

        // When snapshotBarTop is null
        val anchor3 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = 50f,
            snapshotBarTop = null,
        )
        assertEquals(50f, anchor3!!, 0.001f)

        // When both are null, returns fallback
        val anchor4 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = null,
            snapshotBarTop = null,
            fallbackTop = 12f,
        )
        assertEquals(12f, anchor4!!, 0.001f)

        // When all null, returns null
        val anchor5 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = null,
            snapshotBarTop = null,
        )
        assertNull(anchor5)
    }

    @Test
    fun `resolveRainAboveAnchorPlacement places rain label above anchor with gap`() {
        // High top at 56, gap of 3, rain descent 4, ascent -14 (height 18)
        // baseline = 56 - 3 - 4 = 49
        // top = 49 - 14 = 35
        // bottom = 49 + 4 = 53
        val placement = DailyRainLabelPlanner.resolveRainAboveAnchorPlacement(
            anchorTop = 56f,
            ascent = -14f,
            descent = 4f,
            topMargin = 8f,
            gap = 3f,
        )

        assertTrue(placement.fits)
        assertEquals(49f, placement.baseline, 0.01f)
        assertEquals(35f, placement.top, 0.01f)
        assertEquals(53f, placement.bottom, 0.01f)
        assertEquals(56f, placement.anchorTop, 0.01f)
    }

    @Test
    fun `resolveRainAboveAnchorPlacement detects when label exceeds top margin`() {
        val placement = DailyRainLabelPlanner.resolveRainAboveAnchorPlacement(
            anchorTop = 15f,
            ascent = -14f,
            descent = 4f,
            topMargin = 8f,
            gap = 3f,
        )

        assertFalse(placement.fits)
        // baseline = 15 - 3 - 4 = 8
        // top = 8 - 14 = -6 (violates topMargin = 8)
        assertEquals(-6f, placement.top, 0.01f)
    }

    @Test
    fun `resolveRainAboveHighPlacement uses snapshotBarTop when taller than high label`() {
        // highBaseline = 80, ascent = -24 => highLabelTop = 56
        // snapshotBarTop = 30 (taller / smaller Y)
        val placement = DailyRainLabelPlanner.resolveRainAboveHighPlacement(
            highBaseline = 80f,
            highMetrics = DailyRainLabelPlanner.TextMetrics(ascent = -24f, descent = 6f),
            rainMetrics = DailyRainLabelPlanner.TextMetrics(ascent = -14f, descent = 4f),
            topMargin = 8f,
            gap = 3f,
            snapshotBarTop = 30f,
        )

        assertEquals(30f, placement.anchorTop, 0.01f)
        // baseline = 30 - 3 - 4 = 23
        assertEquals(23f, placement.baseline, 0.01f)
        assertEquals(27f, placement.bottom, 0.01f)
        assertEquals(9f, placement.top, 0.01f)
        assertTrue(placement.fits)
    }

    @Test
    fun `resolveRainAboveAnchorTop calculates correct top for bounding boxes`() {
        val top = DailyRainLabelPlanner.resolveRainAboveAnchorTop(
            anchorTop = 50f,
            rainHeight = 15f,
            gapPx = -3f, // negative gap = tuck 3px
            floorY = 5f,
        )
        // 50 - (-3) - 15 = 38
        assertEquals(38f, top, 0.01f)

        // Clamping to floor
        val clampedTop = DailyRainLabelPlanner.resolveRainAboveAnchorTop(
            anchorTop = 10f,
            rainHeight = 15f,
            gapPx = 0f,
            floorY = 5f,
        )
        // 10 - 0 - 15 = -5 => clamped to 5
        assertEquals(5f, clampedTop, 0.01f)
    }
}
