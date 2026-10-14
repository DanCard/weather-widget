package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The observed low is forced tight below its trough and steps over whatever already sits there. A
 * neighbouring label that only clips its side by a sliver must be cleared by a short slide, not a
 * full label-height step: desktop 2026-10-14 drew the 48.3° actual low ~90 px under its line because
 * the 46.6° forecast low's box overlapped it by 0.85 px in x.
 *
 * Geometry is the live render's (`ExactFitPreCheck` / `PlaceAccept` trace).
 */
@Category(ShortDuration::class)
class ActualExtremeSideSlideTest {

    private val widthPx = 809
    private val heightPx = 644
    private val ascent = -36f
    private val descent = 9f
    private val troughY = 506.4f

    // The already-placed forecast LOW "46.6", right edge 175.33.
    private val forecastLowBox = GraphRect(94.33f, 534.5f, 175.33f, 579.5f)

    private fun placeActualLow(centerX: Float): PlacedLabel? {
        val geometry = ResolvedLabelGeometry(
            index = 62, role = TemperatureRole.ACTUAL_LOW, rawTemperature = 48.3f, displayTemperature = 48.3f,
            label = "48.3°", isFuture = false, isValley = true, isEssential = true,
            sx = centerX, sy = troughY, textWidth = 81f, clampedX = centerX,
        )
        val candidate = TempLabelCandidate(
            index = 62, role = TemperatureRole.ACTUAL_LOW, labelTemps = List(63) { 48.3f },
            rawTemperature = 48.3f, forceForecastSeries = false,
        )
        val metas = mutableListOf(PlacedLabelMeta(forecastLowBox, isValleyBelow = true, role = TemperatureRole.LOW, temperature = 46.6f))
        val results = mutableListOf<PlacedLabel>()
        ActualExtremePlacers.place(
            placeAbove = false, widthPx = widthPx, heightPx = heightPx, density = 1f,
            actualVisiblePoints = listOf(100f to 490f, centerX to troughY, 330f to 480f),
            candidate = candidate, geometry = geometry, labelAscent = ascent, labelDescent = descent,
            drawnLabelMetas = metas, drawnIconBounds = emptyList(), reservedHardBounds = emptyList(),
            idx = 62, temps = List(63) { 48.3f }, resultPlacements = results,
        )
        return results.singleOrNull()
    }

    @Test
    fun `a sliver of side overlap slides the actual low over, keeping it on its trough`() {
        val placed = placeActualLow(centerX = 214.98f)!!
        val top = placed.baselineY + ascent

        assertTrue("label stays tight under the trough (top=$top)", top < troughY + 2f)
        assertTrue("label slid right past the forecast low (x=${placed.x})", placed.x - 40.5f >= forecastLowBox.right)
        assertEquals("slide is only overlap + stack gap", 214.98f + 0.85f + 1.5f, placed.x, 0.05f)
    }

    @Test
    fun `a head-on blocker still steps the actual low below it`() {
        val placed = placeActualLow(centerX = 150f)!!
        val top = placed.baselineY + ascent

        assertEquals("x unchanged", 150f, placed.x, 0.01f)
        assertTrue("stepped below the forecast low (top=$top)", top >= forecastLowBox.bottom)
    }
}
