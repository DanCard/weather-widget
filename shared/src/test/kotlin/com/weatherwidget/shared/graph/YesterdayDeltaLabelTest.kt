package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YesterdayDeltaLabelTest {

    // A flat curve sitting near the bottom of the plot, leaving the top band empty.
    private val lowCurve: (Float) -> Float? = { 180f }
    private val metrics = YesterdayDeltaLabel.Metrics(width = 120f, ascent = -10f, descent = 4f) // height 14
    private val plot = GraphRect(0f, 0f, 400f, 200f)

    @Test
    fun `format is signed with one decimal`() {
        assertEquals("+0.4 from yesterday", YesterdayDeltaLabel.format(0.4f))
        assertEquals("-1.2 from yesterday", YesterdayDeltaLabel.format(-1.2f))
        assertEquals("+0.0 from yesterday", YesterdayDeltaLabel.format(0f))
        assertEquals("+0.0 from yesterday", YesterdayDeltaLabel.format(-0.04f)) // rounds to 0, no "-0.0"
        assertEquals("+5.0 from yesterday", YesterdayDeltaLabel.format(4.96f))
    }

    @Test
    fun `color comes from the thermostat model at current temp`() {
        assertEquals(TemperatureColorModel.tempToColorArgb(72f), YesterdayDeltaLabel.colorArgb(72f))
    }

    @Test
    fun `places into the empty band clear of the curve`() {
        val p = YesterdayDeltaLabel.place(
            delta = 0.4f, currentTemp = 72f, spanHours = 6,
            plot = plot, drawnBounds = emptyList(), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
        )
        assertNotNull(p)
        // The box must clear the low-sitting curve (curve at y=180; box stays above it).
        assertTrue(p!!.box.bottom < 180f)
        assertTrue(p.box.top >= plot.top)
        // baseline is below the box top by |ascent|.
        assertEquals(p.box.top + 10f, p.baselineY, 0.001f)
    }

    @Test
    fun `shows in the 24h view, suppressed only past the day-span max`() {
        // 24h view (span 24) shows; the 3-day view (span 72) is suppressed.
        assertNotNull(
            YesterdayDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = 24,
                plot = plot, drawnBounds = emptyList(), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
            ),
        )
        assertNull(
            YesterdayDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = 72,
                plot = plot, drawnBounds = emptyList(), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
            ),
        )
        // boundary is inclusive.
        assertNotNull(
            YesterdayDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = YesterdayDeltaLabel.DELTA_LABEL_MAX_HOURS_SPAN,
                plot = plot, drawnBounds = emptyList(), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
            ),
        )
        assertNull(
            YesterdayDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = YesterdayDeltaLabel.DELTA_LABEL_MAX_HOURS_SPAN + 1,
                plot = plot, drawnBounds = emptyList(), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
            ),
        )
    }

    @Test
    fun `suppressed when delta is null`() {
        assertNull(
            YesterdayDeltaLabel.place(
                delta = null, currentTemp = 72f, spanHours = 6,
                plot = plot, drawnBounds = emptyList(), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
            ),
        )
    }

    @Test
    fun `avoids overlapping an existing label`() {
        // Block the central upper band; the engine must place somewhere not intersecting it.
        val blocker = GraphRect(100f, 0f, 300f, 120f)
        val p = YesterdayDeltaLabel.place(
            delta = 0.4f, currentTemp = 72f, spanHours = 6,
            plot = plot, drawnBounds = listOf(blocker), curveYAt = lowCurve, metrics = metrics, padPx = 4f,
        )
        assertNotNull(p)
        assertTrue(!p!!.box.intersects(blocker))
    }

    @Test
    fun `null when the plot is too short to fit the label with padding`() {
        // Plot height 20 < label height (14) + 2*pad (8): no vertical room for any candidate box.
        val p = YesterdayDeltaLabel.place(
            delta = 0.4f, currentTemp = 72f, spanHours = 6,
            plot = GraphRect(0f, 0f, 400f, 20f), drawnBounds = emptyList(),
            curveYAt = { null }, metrics = metrics, padPx = 4f,
        )
        assertNull(p)
    }
}
