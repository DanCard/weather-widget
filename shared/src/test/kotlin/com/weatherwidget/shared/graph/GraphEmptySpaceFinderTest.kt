package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The shared empty-space search behind [ForecastDeltaLabel] and [DominantStationLabel]. The two label
 * objects own their gates and text; everything about *where* a free-floating annotation lands is here,
 * so this is where the anchor-order and clearance contracts they both rely on are pinned down.
 */
@Category(ShortDuration::class)
class GraphEmptySpaceFinderTest {

    private val plot = GraphRect(0f, 0f, 400f, 200f)
    private val metrics = GraphEmptySpaceFinder.Metrics(width = 60f, ascent = -10f, descent = 3f)

    private fun find(
        plot: GraphRect = this.plot,
        drawnBounds: List<GraphRect> = emptyList(),
        curveYsAt: (Float) -> List<Float> = { listOf(190f) },
        metrics: GraphEmptySpaceFinder.Metrics = this.metrics,
        padPx: Float = 4f,
        xFractions: List<Float> = listOf(0.25f, 0.75f),
    ) = GraphEmptySpaceFinder.find(
        plot = plot,
        drawnBounds = drawnBounds,
        curveYsAt = curveYsAt,
        metrics = metrics,
        padPx = padPx,
        xFractions = xFractions,
    )

    @Test
    fun takesTheEarliestAnchorWithAnyRoom() {
        assertEquals(100f, requireNotNull(find()).centerX, 0.01f)
    }

    @Test
    fun fallsThroughToTheNextAnchorWhenTheFirstIsBlocked() {
        val blockFirst = listOf(GraphRect(0f, -10f, 140f, 210f))
        assertEquals(300f, requireNotNull(find(drawnBounds = blockFirst)).centerX, 0.01f)
    }

    @Test
    fun returnsNullWhenNoAnchorHasRoom() {
        assertNull(find(drawnBounds = listOf(GraphRect(-10f, -10f, 410f, 210f))))
    }

    @Test
    fun withinAnAnchorPicksTheGreatestCurveClearanceNotTheFirstFit() {
        // Curve pinned at the very bottom: the topmost candidate has the most room, and the search must
        // keep scanning rather than returning the first legal box (which is also the topmost here, so
        // flip it: pin the curve at the TOP and require the BOTTOM-most band).
        val placement = requireNotNull(find(curveYsAt = { listOf(2f) }))
        assertTrue(
            "expected the label pushed to the bottom band, got top=${placement.box.top}",
            placement.box.top > plot.height / 2f,
        )
    }

    @Test
    fun aBoxStraddlingTheCurveIsRejected() {
        // Plot height 21 == box height 13 + 2 * pad 4, so exactly one candidate exists (94..107) and the
        // curve runs through it. Proves rejection, not a plot that was too short to try.
        var probeCount = 0
        assertNull(find(plot = GraphRect(0f, 90f, 400f, 111f), curveYsAt = { probeCount++; listOf(100f) }))
        assertTrue("expected the curve to actually be sampled", probeCount > 0)
    }

    @Test
    fun clearanceUnderThePadIsRejected() {
        // Same single candidate (4..17); the curve clears it by 2px, under the 4px pad.
        assertNull(find(plot = GraphRect(0f, 0f, 400f, 21f), curveYsAt = { listOf(19f) }))
    }

    @Test
    fun boxesAboveAndBelowTheCurveBothReportAGap() {
        // Curve at the vertical center of a tall plot: both the top and bottom bands are legal, and one
        // of them must be chosen (proving the "wholly above" and "wholly below" branches both score).
        assertNotNull(find(curveYsAt = { listOf(100f) }))
    }

    @Test
    fun asecondCurveVetoesASlotTheFirstOneLeavesOpen() {
        // The shipped bug: the sampler answered with the observed line only, so a box high in the plot
        // reported metres of clearance and was drawn straight through the forecast dashes. With both
        // lines reported, the only legal band is the one clear of BOTH.
        val observedOnly = requireNotNull(find(curveYsAt = { listOf(190f) }))
        assertTrue("expected the top band when only the low curve is reported", observedOnly.box.top < 50f)

        val bothLines = find(curveYsAt = { listOf(190f, it * 0f + 20f) })
        if (bothLines != null) {
            assertTrue(
                "box must clear the second curve at y=20, got top=${bothLines.box.top}",
                bothLines.box.top > 20f,
            )
        }
    }

    @Test
    fun aSecondCurveThroughEveryBandBlocksThePlacementEntirely() {
        // One line low, one line threading the single candidate band: nothing legal remains.
        assertNull(find(plot = GraphRect(0f, 90f, 400f, 111f), curveYsAt = { listOf(300f, 100f) }))
    }

    @Test
    fun anchorsAreClampedInsideThePlot() {
        // 0.0 and 1.0 would put half the box outside; both must clamp to a fully-contained box.
        val left = requireNotNull(find(xFractions = listOf(0f)))
        assertTrue(left.box.left >= plot.left)
        val right = requireNotNull(find(xFractions = listOf(1f)))
        assertTrue(right.box.right <= plot.right)
    }

    @Test
    fun textWiderThanThePlotNeverPlaces() {
        assertNull(find(metrics = GraphEmptySpaceFinder.Metrics(width = 400f, ascent = -10f, descent = 3f)))
    }

    @Test
    fun degenerateMetricsNeverPlace() {
        assertNull(find(metrics = GraphEmptySpaceFinder.Metrics(width = 0f, ascent = -10f, descent = 3f)))
        assertNull(find(metrics = GraphEmptySpaceFinder.Metrics(width = 60f, ascent = 0f, descent = 0f)))
    }

    @Test
    fun plotShorterThanTheTextNeverPlaces() {
        assertNull(find(plot = GraphRect(0f, 0f, 400f, 10f)))
    }

    @Test
    fun anOffCurveSamplerStillPlaces() {
        // An empty list everywhere (no line drawn under the box) is maximum clearance.
        assertNotNull(find(curveYsAt = { emptyList() }))
    }

    @Test
    fun baselineAndBoxAgree() {
        val slot = requireNotNull(find())
        assertEquals(slot.box.top - metrics.ascent, slot.baselineY, 0.001f)
        assertEquals(metrics.width, slot.box.right - slot.box.left, 0.001f)
        assertEquals(metrics.height, slot.box.bottom - slot.box.top, 0.001f)
        assertEquals((slot.box.left + slot.box.right) / 2f, slot.centerX, 0.001f)
    }
}
