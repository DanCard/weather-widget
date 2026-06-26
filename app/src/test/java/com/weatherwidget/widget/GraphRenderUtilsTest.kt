package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class GraphRenderUtilsTest {
    @Test
    fun `smoothValuesPreservingGlobalExtrema keeps extrema and endpoints exact across iterations`() {
        val values = listOf(10f, 80f, 20f, 60f, 5f)

        val result = GraphRenderUtils.smoothValuesPreservingGlobalExtrema(values, iterations = 3)

        assertEquals(10f, result[0], 0.0001f)
        assertEquals(80f, result[1], 0.0001f)
        assertEquals(5f, result[4], 0.0001f)
    }

    @Test
    fun `dayLabelEndpoints derives today, edge dates and short day names`() {
        val first = java.time.LocalDateTime.of(2026, 6, 15, 8, 0)   // Monday
        val last = java.time.LocalDateTime.of(2026, 6, 16, 20, 0)   // Tuesday
        val now = java.time.LocalDateTime.of(2026, 6, 16, 12, 0)    // Tuesday

        val e = GraphRenderUtils.dayLabelEndpoints(first, last, now)

        assertEquals(java.time.LocalDate.of(2026, 6, 16), e.today)
        assertEquals(java.time.LocalDate.of(2026, 6, 15), e.leftDate)
        assertEquals(java.time.LocalDate.of(2026, 6, 16), e.rightDate)
        // Day-of-week short names (default locale); assert via the same API to stay locale-agnostic.
        assertEquals(first.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()), e.leftText)
        assertEquals(last.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()), e.rightText)
    }

    @Test
    fun `smoothValuesPreservingGlobalExtrema reapplies anchors each iteration`() {
        val values = listOf(0f, 100f, 0f, 0f, 0f)

        val result = GraphRenderUtils.smoothValuesPreservingGlobalExtrema(values, iterations = 2)

        assertEquals(37.5f, result[2], 0.0001f)
        assertNotEquals(25f, result[2], 0.0001f)
    }

    @Test
    fun `smoothValuesPreservingExtrema can leave global minimum smoothed while preserving max and endpoints`() {
        val values = listOf(40f, 0f, 100f, 0f, 40f)

        val result =
            GraphRenderUtils.smoothValuesPreservingExtrema(
                values = values,
                iterations = 2,
                preserveGlobalMax = true,
                preserveGlobalMin = false,
                preserveStart = true,
                preserveEnd = true,
            )

        assertEquals(40f, result[0], 0.0001f)
        assertEquals(100f, result[2], 0.0001f)
        assertEquals(40f, result[4], 0.0001f)
        assertEquals(52.5f, result[1], 0.0001f)
        assertEquals(52.5f, result[3], 0.0001f)
    }

    @Test
    fun `placeDateLabelCenter clamps an edge label inward instead of dropping it when there is room`() {
        // 60px-wide label (half-extent 30) near the left edge on a wide canvas with no prior label:
        // clamp its center to 30 so it sits flush on-canvas rather than being dropped.
        val clamped = GraphRenderUtils.placeDateLabelCenter(
            centerX = 5f, leftExtent = 30f, rightExtent = 30f, widthPx = 800,
            prevRightPx = Float.NEGATIVE_INFINITY, minGapPx = 6f,
        )
        assertEquals(30f, clamped!!, 0.001f)
    }

    @Test
    fun `placeDateLabelCenter drops a label that would overlap the previous one`() {
        // Previous label ended at x=100; a label clamped to center 110 (half 30) would start at 80,
        // inside 100 + 6 gap -> drop.
        val dropped = GraphRenderUtils.placeDateLabelCenter(
            centerX = 110f, leftExtent = 30f, rightExtent = 30f, widthPx = 800,
            prevRightPx = 100f, minGapPx = 6f,
        )
        assertEquals(null, dropped)
    }

    @Test
    fun `placeDateLabelCenter drops a label wider than the canvas`() {
        val dropped = GraphRenderUtils.placeDateLabelCenter(
            centerX = 100f, leftExtent = 120f, rightExtent = 120f, widthPx = 200,
            prevRightPx = Float.NEGATIVE_INFINITY, minGapPx = 6f,
        )
        assertEquals(null, dropped)
    }

    @Test
    fun `placeDateLabelCenter keeps a well-separated label`() {
        // Previous ended at 100; this one centers at 400 (half 30) -> starts at 370, clear -> keep.
        val clamped = GraphRenderUtils.placeDateLabelCenter(
            centerX = 400f, leftExtent = 30f, rightExtent = 30f, widthPx = 800,
            prevRightPx = 100f, minGapPx = 6f,
        )
        assertEquals(400f, clamped!!, 0.001f)
    }

    @Test
    fun `smoothValuesPreservingAllExtrema preserves every local peak and valley`() {
        // Dynamic data with two distinct peaks and a valley
        val values = listOf(20f, 80f, 30f, 60f, 10f)

        val result = GraphRenderUtils.smoothValuesPreservingAllExtrema(values, iterations = 3)

        // All points are extrema or endpoints in this small set, so all should be preserved perfectly
        assertEquals(20f, result[0], 0.0001f) // Start
        assertEquals(80f, result[1], 0.0001f) // Local Peak 1
        assertEquals(30f, result[2], 0.0001f) // Local Valley
        assertEquals(60f, result[3], 0.0001f) // Local Peak 2
        assertEquals(10f, result[4], 0.0001f) // End
    }
}
