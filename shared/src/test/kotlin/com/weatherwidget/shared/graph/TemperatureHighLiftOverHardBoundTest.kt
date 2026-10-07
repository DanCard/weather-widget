package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Fold 4, 2026-10-07: the forecast high 82.3° sat just right of the fetch dot, whose "81.9°" side
 * label (a hard bound) covered the bottom 7 px of the high's above-slot. Steps move a whole label
 * height, so the high went below its peak with a long leader. Two highs near the top now lift the
 * forecast high by just its ink overlap instead of flipping it below.
 * See plans/261007-forecast-high-lift-over-fetch-dot-and-centered-quota-pill.md.
 */
@Category(ShortDuration::class)
class TemperatureHighLiftOverHardBoundTest {

    // Device-like proportions: a 30 px box whose bottom 6 px is the (blank) descent band.
    private class Metrics : LabelTextMetrics {
        override val ascent: Float = -24f
        override val descent: Float = 6f
        override fun width(text: String, isFuture: Boolean): Float = text.length * 12f
    }

    private val widthPx = 600
    private val heightPx = 400
    private val graphTop = 44f
    private val peakIdx = 12
    private val peakTemp = 82.3f
    private val forecast = List(24) { i -> peakTemp - kotlin.math.abs(i - peakIdx) * 1.5f }
    private val minTemp = forecast.min() - 5f
    private val maxTemp = forecast.max() + 5f
    private val hourWidth = widthPx.toFloat() / forecast.size

    private fun tempToY(t: Float): Float {
        val gh = (heightPx - 30f) - graphTop
        return graphTop + gh * (1f - (t - minTemp) / (maxTemp - minTemp))
    }

    private val peakX = peakIdx * hourWidth
    private val peakY = tempToY(peakTemp)

    // Where the high's above-slot box would sit with no obstacle (the engine's TEMP above gap is 1 dp).
    private val naturalBottom = peakY - GraphLabelPlacementUtils.TEMP_PREFERRED_ABOVE_GAP_DP

    private fun hours(): List<HourData> {
        val start = LocalDateTime.of(2026, 10, 7, 3, 0)
        return forecast.mapIndexed { i, t ->
            val dt = start.plusHours(i.toLong())
            HourData(dateTime = dt, temperature = t, label = "${dt.hour}", showLabel = true)
        }
    }

    /**
     * The fetch-dot side label, its top [overlapPx] above the high's natural box bottom. Its x-span
     * covers the peak's centre (Fold: centre 283 inside 270–328), so the overlap is head-on and the
     * side-only minor-overlap allowance does not apply.
     */
    private fun sideLabel(overlapPx: Float) =
        GraphRect(peakX - 13f, naturalBottom - overlapPx, peakX + 45f, naturalBottom - overlapPx + 30f)

    private fun placeHigh(hardBounds: List<GraphRect>): PlacedLabel {
        val hours = hours()
        val epoch0 = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val points = hours.map { ((it.dateTime.toEpochSecond(ZoneOffset.UTC) - epoch0) / 3600f) * hourWidth to tempToY(it.temperature) }
        val placements = TemperatureLabelEngine.computePlacements(
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
            tempToY = ::tempToY,
            metrics = Metrics(),
            reservedHardBounds = hardBounds,
            useCelsius = false,
        )
        val high = placements.find { it.index == peakIdx }
        assertNotNull("Expected the peak to be labeled. placements=$placements", high)
        return high!!
    }

    @Test
    fun `control - unobstructed high sits directly above its peak`() {
        val high = placeHigh(emptyList())
        assertTrue("placedAbove", high.placedAbove)
        assertEquals("above", high.reason)
        assertFalse("no leader", high.drawLeaderLine)
    }

    @Test
    fun `overlap inside the blank descent band lifts by a pixel and draws no leader`() {
        val high = placeHigh(listOf(sideLabel(overlapPx = 5f)))
        assertTrue("Must stay above its peak, not flip below. high=$high", high.placedAbove)
        assertEquals("above+lift", high.reason)
        assertFalse("A lift inside the descent band needs no leader. high=$high", high.drawLeaderLine)
        val naturalBaseline = naturalBottom - 6f
        assertEquals("Lift is clearance only (1 dp)", naturalBaseline - 1f, high.baselineY, 0.01f)
    }

    @Test
    fun `deeper overlap lifts by the ink overlap - far less than a label height - with a short leader`() {
        val overlap = 12f
        val sideLabel = sideLabel(overlap)
        val high = placeHigh(listOf(sideLabel))
        assertTrue("Must stay above its peak, not flip below. high=$high", high.placedAbove)
        assertEquals("above+lift", high.reason)
        assertTrue("A lift beyond the descent band keeps a leader. high=$high", high.drawLeaderLine)

        // Lift = (overlap − descent) + 1 dp clearance = 7 px, not a whole 30 px step.
        val naturalBaseline = naturalBottom - 6f
        assertEquals(naturalBaseline - 7f, high.baselineY, 0.01f)
        assertTrue("Ink (baseline) must clear the side label. high=$high", high.baselineY <= sideLabel.top)
        assertEquals("Leader runs from the peak to the baseline", high.baselineY, high.leaderToY, 0.01f)
        assertTrue("Leader is shorter than a label height", high.leaderFromY - high.leaderToY < 30f)
    }
}
