package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Coverage for the "today-low leader line at the NOW valley" fix (Samsung, 2026-06-18).
 *
 * A reserved hard bound (the fetch-dot value/age label) is no longer an *absolute* blocker: a label
 * may take the SAME minor (whitespace-level) overlap with it that the engine already tolerates for
 * placed labels — `shouldAllowMinorOverlap(role, overlap, MINOR_OVERLAP_HEIGHT_RATIO=0.30)`. So a
 * valley low whose just-below slot only grazes the hard bound hugs its valley at step 0 (no leader)
 * instead of dropping a full label-height. A real (> budget) overlap still blocks — preserving the
 * `260612` "631°" flip-above (guarded by TemperatureLabelFetchDotHardBoundsTest).
 */
class TemperatureLabelHardBoundMinorOverlapTest {

    private fun buildHours(
        forecastTemps: List<Float>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 4, 8, 0, 0),
    ): List<HourData> =
        forecastTemps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = temp,
                actualTemperature = null,
                isActual = false,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    // ascent/descent give labelHeight = descent - ascent = 12; minor-overlap budget = 0.30*12 = 3.6px.
    private class TestLabelTextMetrics(
        val charWidth: Float = 6f,
        override val ascent: Float = -10f,
        override val descent: Float = 2f
    ) : LabelTextMetrics {
        override fun width(text: String, isFuture: Boolean): Float = text.length * charWidth
    }

    private val graphTop = 44f
    private fun graphBottom(heightPx: Int) = heightPx - 30f

    private fun tempToYFor(heightPx: Int, minTemp: Float, maxTemp: Float): (Float) -> Float {
        val range = maxTemp - minTemp
        val gh = graphBottom(heightPx) - graphTop
        return { t -> graphTop + gh * (1f - (t - minTemp) / range) }
    }

    private fun runEngineTest(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        reservedHardBounds: List<GraphRect> = emptyList(),
        metrics: LabelTextMetrics = TestLabelTextMetrics(),
    ): List<PlacedLabel> {
        val allTemps = hours.map { it.temperature }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val tempToY = tempToYFor(heightPx, minTemp, maxTemp)

        val minTimeEpoch = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        val points = hours.map {
            val x = ((it.dateTime.toEpochSecond(ZoneOffset.UTC) - minTimeEpoch) / 3600f) * hourWidth
            x to tempToY(it.temperature)
        }

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = points,
            forecastPoints = points,
            actualVisiblePoints = emptyList(),
            transitionX = null,
            fetchDotX = null,
            lastObservedTemp = null,
            observedAt = null,
            effectiveActualEndIndex = -1,
            fetchTime = null,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = metrics,
            reservedHardBounds = reservedHardBounds,
        )
    }

    private fun valleyAnchorY(valleyTemp: Float, hours: List<HourData>, heightPx: Int): Float {
        val allTemps = hours.map { it.temperature }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        return tempToYFor(heightPx, minTemp, maxTemp)(valleyTemp)
    }

    private fun valleyCenterX(valleyIdx: Int, hours: List<HourData>, widthPx: Int): Float =
        valleyIdx * (widthPx.toFloat() / hours.size.toFloat())

    /**
     * Hard bound overlapping the just-below slot (the below label rect is anchorY+1..+13) by
     * `overlapPx` vertically. `sideOffset` shifts it horizontally relative to the valley center:
     * 0 = head-on (centered on the label); a large positive value places it beside the label so the
     * label's center-x is outside the bound (the Samsung "value label sits to the right" geometry).
     */
    private fun belowSlotHardBound(valleyTemp: Float, valleyIdx: Int, hours: List<HourData>, widthPx: Int, heightPx: Int, overlapPx: Float, sideOffset: Float): GraphRect {
        val anchorY = valleyAnchorY(valleyTemp, hours, heightPx)
        val cx = valleyCenterX(valleyIdx, hours, widthPx)
        val top = anchorY + 13f - overlapPx
        return GraphRect(cx + sideOffset, top, cx + sideOffset + 45f, anchorY + 80f)
    }

    @Test
    fun `side-offset minor hard overlap keeps the valley low at its anchor with no leader`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val hours = buildHours(forecast)

        // Beside the label (center-x outside the bound) + ~3px vertical overlap < 3.6px budget.
        // Mirrors Samsung: the 58.8 low sits to the LEFT of the fetch-dot value label.
        val hard = belowSlotHardBound(52f, 12, hours, 300, 400, overlapPx = 3f, sideOffset = 6f)
        val placements = runEngineTest(hours, 300, 400, reservedHardBounds = listOf(hard))
        val low = placements.find { it.displayTemperature == 52f }
        assertNotNull("Low label should be placed", low)
        assertFalse("Side-offset minor overlap must keep the low below its valley (reason=${low!!.reason})", low.placedAbove)
        assertEquals("Low should sit at step 0 (no displacement)", 0, low.displacementSteps)
        assertFalse("No leader line when placed at the anchor", low.drawLeaderLine)
    }

    @Test
    fun `head-on hard overlap still displaces the valley low (631 guard)`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val hours = buildHours(forecast)

        // Centered on the label (head-on): even a minor vertical overlap is a real glyph collision,
        // so the low must NOT stay flush below — it flips/displaces (preserves the 260612 fix).
        val hard = belowSlotHardBound(52f, 12, hours, 300, 400, overlapPx = 4f, sideOffset = -22f)
        val placements = runEngineTest(hours, 300, 400, reservedHardBounds = listOf(hard))
        val low = placements.find { it.displayTemperature == 52f }
        assertNotNull("Low label should be placed", low)
        val displacedOrFlipped = low!!.placedAbove || low.displacementSteps > 0
        assertTrue(
            "A head-on hard overlap must not leave the low flush below at step 0 (placedAbove=${low.placedAbove}, steps=${low.displacementSteps}, reason=${low.reason})",
            displacedOrFlipped
        )
    }

    @Test
    fun `side-offset major hard overlap still displaces the valley low (budget still applies)`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val hours = buildHours(forecast)

        // Beside the label but ~12px vertical overlap > 5.4px budget => still blocks the anchor slot.
        val hard = belowSlotHardBound(52f, 12, hours, 300, 400, overlapPx = 12f, sideOffset = 6f)
        val placements = runEngineTest(hours, 300, 400, reservedHardBounds = listOf(hard))
        val low = placements.find { it.displayTemperature == 52f }
        assertNotNull("Low label should be placed", low)
        val displacedOrFlipped = low!!.placedAbove || low.displacementSteps > 0
        assertTrue(
            "A > budget hard overlap must not leave the low flush below at step 0 (placedAbove=${low.placedAbove}, steps=${low.displacementSteps}, reason=${low.reason})",
            displacedOrFlipped
        )
    }

    @Test
    fun `empty hard bounds are unchanged`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val hours = buildHours(forecast)

        val withDefault = runEngineTest(hours, 300, 400)
        val withEmpty = runEngineTest(hours, 300, 400, reservedHardBounds = emptyList())
        val a = withDefault.find { it.displayTemperature == 52f }!!
        val b = withEmpty.find { it.displayTemperature == 52f }!!
        assertEquals(a.placedAbove, b.placedAbove)
        assertEquals(a.baselineY, b.baselineY, 0.001f)
        assertEquals(a.displacementSteps, b.displacementSteps)
    }
}
