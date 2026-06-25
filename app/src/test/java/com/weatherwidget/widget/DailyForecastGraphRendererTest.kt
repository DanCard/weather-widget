package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import kotlin.math.roundToInt

@Category(ShortDuration::class)
class DailyForecastGraphRendererTest {

    @Test
    fun `formatTempLabel rounds to integer when forceInteger`() {
        val renderer = DailyForecastGraphRenderer
        assertEquals("49°", renderer.formatTempLabel(48.6f, forceInteger = true))
    }

    @Test
    fun `formatTempLabel preserves decimal for fractional part by default`() {
        val renderer = DailyForecastGraphRenderer
        assertEquals("48.6°", renderer.formatTempLabel(48.6f))
    }

    @Test
    fun `formatTempLabel suppresses the point-zero case`() {
        val renderer = DailyForecastGraphRenderer
        assertEquals("49°", renderer.formatTempLabel(49.0f))
    }

    @Test
    fun `fitScaleForWidth leaves a label that already fits unchanged`() {
        // "78°" comfortably inside the column: no shrink.
        assertEquals(1f, DailyForecastGraphRenderer.fitScaleForWidth(40f, maxWidthPx = 60f, currentScale = 1f))
    }

    @Test
    fun `fitScaleForWidth shrinks an overflowing label to fit the column`() {
        // 80px label, 60px budget -> scale down to 0.75 so it fits.
        assertEquals(0.75f, DailyForecastGraphRenderer.fitScaleForWidth(80f, maxWidthPx = 60f, currentScale = 1f), 0.0001f)
    }

    @Test
    fun `fitScaleForWidth never shrinks below the legibility floor`() {
        // Wildly oversized label clamps to currentScale * minScale (legibility floor), not to 0.
        val minScale = 0.7f
        assertEquals(
            0.7f,
            DailyForecastGraphRenderer.fitScaleForWidth(1000f, maxWidthPx = 10f, currentScale = 1f, minScale = minScale),
            0.0001f,
        )
    }

    @Test
    fun `fitScaleForWidth composes with an existing wide-label scale`() {
        // Starting from the 0.95 wide-label step, an 80px@0.95 label in a 60px column shrinks
        // further to 0.95 * 0.75 (still above the 0.95 * 0.7 floor).
        val current = 0.95f
        val result = DailyForecastGraphRenderer.fitScaleForWidth(80f, maxWidthPx = 60f, currentScale = current)
        assertEquals(current * (60f / 80f), result, 0.0001f)
    }

    @Test
    fun `resolveBottomStackLow prefers explicit bottom stack low over observed low`() {
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2030, 6, 15),
            label = "Today",
            solidLineHigh = 74f,
            solidLineLow = 67f,
            bottomStackLow = 65f,
            dashedLineLow = 65f,
            isToday = true,
        )

        assertEquals(65f, DailyForecastGraphRenderer.resolveBottomStackLow(day)!!, 0.1f)
    }

    @Test
    fun `resolveBottomStackLow falls back to observed low when explicit anchor is absent`() {
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2030, 6, 16),
            label = "Sun",
            solidLineHigh = 76f,
            solidLineLow = 58f,
        )

        assertEquals(58f, DailyForecastGraphRenderer.resolveBottomStackLow(day)!!, 0.1f)
    }

    @Test
    fun `resolveRainAboveHighPlacement fits when rain label clears top margin`() {
        val placement = DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement(
            highBaseline = 80f,
            highMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -24f, descent = 6f),
            rainMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -14f, descent = 4f),
            topMargin = 8f,
            gap = 3f,
        )

        assertEquals(true, placement.fits)
        assertEquals(49f, placement.baseline, 0.01f)
        assertEquals(53f, placement.bottom, 0.01f)
        assertEquals(56f, placement.highLabelTop, 0.01f)
    }

    @Test
    fun `resolveRainAboveHighPlacement rejects label when top space is insufficient`() {
        val placement = DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement(
            highBaseline = 36f,
            highMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -24f, descent = 6f),
            rainMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -14f, descent = 4f),
            topMargin = 8f,
            gap = 3f,
        )

        assertEquals(false, placement.fits)
    }

    // Samsung repro: the interstitial night label lands beside this day's own low label and clips
    // its degree symbol. It should nudge DOWN to share the low label's baseline (beside the number),
    // never moving sideways or dropping past the baseline.
    @Test
    fun `resolveNightCollision nudges down to share low label baseline when overlapping`() {
        // Night label sits high (baseline 100, top 86) over the low label digits (top 95).
        val result = DailyForecastRainLabelRenderer.resolveNightCollision(
            nightCenterX = 200f,
            nightBaseline = 100f,
            nightHalfWidth = 12f,
            ascent = -14f,
            descent = 4f,
            ownLeft = 180f,
            ownTop = 95f,
            ownRight = 205f,
            ownBottom = 140f,
            ownBaseline = 135f,
        )

        assertEquals("down", result.resolution)
        assertEquals(135f, result.baseline, 0.01f)
        assertEquals(200f, result.centerX, 0.01f) // never moves sideways
    }

    @Test
    fun `resolveNightCollision leaves label when it does not overlap the low label`() {
        // Night label is well to the right of the low label — no horizontal overlap.
        val result = DailyForecastRainLabelRenderer.resolveNightCollision(
            nightCenterX = 260f,
            nightBaseline = 100f,
            nightHalfWidth = 12f,
            ascent = -14f,
            descent = 4f,
            ownLeft = 180f,
            ownTop = 95f,
            ownRight = 205f,
            ownBottom = 140f,
            ownBaseline = 135f,
        )

        assertEquals("none", result.resolution)
        assertEquals(100f, result.baseline, 0.01f)
    }

    @Test
    fun `resolveNightCollision never moves the label up`() {
        // Overlapping, but the night label already sits below the low label baseline — leave it.
        val result = DailyForecastRainLabelRenderer.resolveNightCollision(
            nightCenterX = 200f,
            nightBaseline = 138f,
            nightHalfWidth = 12f,
            ascent = -14f,
            descent = 4f,
            ownLeft = 180f,
            ownTop = 95f,
            ownRight = 205f,
            ownBottom = 140f,
            ownBaseline = 135f,
        )

        assertEquals("none", result.resolution)
        assertEquals(138f, result.baseline, 0.01f)
    }
}
