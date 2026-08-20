package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastDeltaLabelTest {

    // A flat curve sitting near the bottom of the plot, leaving the top band empty.
    private val lowCurve: (Float) -> List<Float> = { listOf(180f) }
    private val metrics = ForecastDeltaLabel.Metrics(width = 120f, ascent = -10f, descent = 4f) // height 14
    private val plot = GraphRect(0f, 0f, 400f, 200f)

    @Test
    fun `format is signed with one decimal`() {
        assertEquals("+0.4 from forecast", ForecastDeltaLabel.format(0.4f, useCelsius = false))
        assertEquals("-1.2 from forecast", ForecastDeltaLabel.format(-1.2f, useCelsius = false))
        assertEquals("+0.0 from forecast", ForecastDeltaLabel.format(0f, useCelsius = false))
        assertEquals("+0.0 from forecast", ForecastDeltaLabel.format(-0.04f, useCelsius = false)) // rounds to 0, no "-0.0"
        assertEquals("+5.0 from forecast", ForecastDeltaLabel.format(4.96f, useCelsius = false))
    }

    @Test
    fun `compact daily rows reuse signed value and abbreviate forecast`() {
        assertEquals("-1.2", ForecastDeltaLabel.formatValue(-1.2f, useCelsius = false))
        assertEquals("fcst", ForecastDeltaLabel.COMPACT_CAPTION)
    }

    @Test
    fun `color comes from the thermostat model at current temp`() {
        assertEquals(TemperatureColorModel.tempToColorArgb(72f), ForecastDeltaLabel.colorArgb(72f))
    }

    @Test
    fun `places into the empty band clear of the curve`() {
        val p = ForecastDeltaLabel.place(
            delta = 0.4f, currentTemp = 72f, spanHours = 6,
            plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
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
            ForecastDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = 24,
                plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
            ),
        )
        assertNull(
            ForecastDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = 72,
                plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
            ),
        )
        // boundary is inclusive.
        assertNotNull(
            ForecastDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = ForecastDeltaLabel.DELTA_LABEL_MAX_HOURS_SPAN,
                plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
            ),
        )
        assertNull(
            ForecastDeltaLabel.place(
                delta = 0.4f, currentTemp = 72f, spanHours = ForecastDeltaLabel.DELTA_LABEL_MAX_HOURS_SPAN + 1,
                plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
            ),
        )
    }

    @Test
    fun `suppressed when delta is null`() {
        assertNull(
            ForecastDeltaLabel.place(
                delta = null, currentTemp = 72f, spanHours = 6,
                plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
            ),
        )
    }

    @Test
    fun `isZero detects deltas that format to zero`() {
        assertTrue(ForecastDeltaLabel.isZero(0f, useCelsius = false))
        assertTrue(ForecastDeltaLabel.isZero(-0.04f, useCelsius = false)) // rounds to 0, no "-0.0"
        assertTrue(ForecastDeltaLabel.isZero(0.04f, useCelsius = false))
        assertFalse(ForecastDeltaLabel.isZero(0.1f, useCelsius = false))
        // 0.04°F = 0.022°C: still zero after the scale-only conversion.
        assertTrue(ForecastDeltaLabel.isZero(0.04f, useCelsius = true))
    }

    @Test
    fun `suppressed when delta rounds to zero`() {
        val zeroDeltas = listOf(0f, 0.04f, -0.04f)
        for (delta in zeroDeltas) {
            assertNull(
                ForecastDeltaLabel.place(
                    delta = delta, currentTemp = 72f, spanHours = 6,
                    plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
                ),
            )
        }
        // A non-zero delta in the same window still places.
        assertNotNull(
            ForecastDeltaLabel.place(
                delta = 0.1f, currentTemp = 72f, spanHours = 6,
                plot = plot, drawnBounds = emptyList(), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
            ),
        )
    }

    @Test
    fun `avoids overlapping an existing label`() {
        // Block the central upper band; the engine must place somewhere not intersecting it.
        val blocker = GraphRect(100f, 0f, 300f, 120f)
        val p = ForecastDeltaLabel.place(
            delta = 0.4f, currentTemp = 72f, spanHours = 6,
            plot = plot, drawnBounds = listOf(blocker), curveYsAt = lowCurve, metrics = metrics, padPx = 4f, useCelsius = false,
        )
        assertNotNull(p)
        assertTrue(!p!!.box.intersects(blocker))
    }

    @Test
    fun `null when the plot is too short to fit the label with padding`() {
        // Plot height 20 < label height (14) + 2*pad (8): no vertical room for any candidate box.
        val p = ForecastDeltaLabel.place(
            delta = 0.4f, currentTemp = 72f, spanHours = 6,
            plot = GraphRect(0f, 0f, 400f, 20f), drawnBounds = emptyList(),
            curveYsAt = { emptyList() }, metrics = metrics, padPx = 4f, useCelsius = false,
        )
        assertNull(p)
    }
}
