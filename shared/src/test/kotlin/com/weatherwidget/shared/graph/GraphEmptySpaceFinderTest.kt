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

    /**
     * Height 21 == box height 13 + 2 * pad 4, so exactly one candidate band (top 4, bottom 17) exists
     * per anchor. Without this the two-pass tests pass for the wrong reason: a tall plot lets a crowded
     * anchor simply drop to its empty bottom band, and the search never has to move sideways.
     */
    private val SINGLE_BAND_PLOT = GraphRect(0f, 0f, 400f, 21f)

    /** Curve 6px under the only band at anchor 0.25 (legal, over the 4px pad) and absent at 0.75. */
    private val TIGHT_UNDER_THE_FIRST_ANCHOR = { x: Float -> if (x < 200f) listOf(23f) else emptyList() }

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

    /** [find] plus veto bounds — the NOW-line channel, which blocks overlap but never repels. */
    private fun findWithVeto(
        vetoBounds: List<GraphRect>,
        plot: GraphRect = this.plot,
        curveYsAt: (Float) -> List<Float> = { listOf(190f) },
    ) = GraphEmptySpaceFinder.find(
        plot = plot,
        drawnBounds = emptyList(),
        curveYsAt = curveYsAt,
        metrics = metrics,
        padPx = 4f,
        xFractions = listOf(0.25f, 0.75f),
        vetoBounds = vetoBounds,
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
    fun aWideOpenLaterAnchorBeatsATightLegalEarlierOne() {
        // The shipped bug, in miniature: anchor 0.25 has a legal box (the curve clears it by 6px, over
        // the 4px pad) so first-fit returned it and never looked at anchor 0.75, where the plot is
        // empty. Plot height 21 == box height 13 + 2 * pad 4, so there is exactly one candidate band
        // (4..17) and the left anchor cannot escape the crowding by dropping down it.
        val placement = requireNotNull(
            find(plot = SINGLE_BAND_PLOT, curveYsAt = TIGHT_UNDER_THE_FIRST_ANCHOR),
        )
        assertEquals(
            "expected the wide-open right anchor, not the 6px-clearance left one",
            300f,
            placement.centerX,
            0.01f,
        )
    }

    @Test
    fun obstacleProximityNotJustOverlapDisqualifiesTheFirstAnchor() {
        // Two labels flanking anchor 0.25 without touching its box (70..130): 4px of air on each side.
        // Curves are metres away, so only the obstacle distance can reject this slot — which is exactly
        // the corner the dominant-station label wedged itself into beside `62°` and `+0.7 from forecast`.
        val flanking = listOf(
            GraphRect(0f, 0f, 66f, 200f),
            GraphRect(134f, 0f, 200f, 200f),
        )
        val placement = requireNotNull(find(drawnBounds = flanking))
        assertEquals("expected the open right anchor", 300f, placement.centerX, 0.01f)
    }

    @Test
    fun aDiagonalNeighbourDoesNotCountAsCrowding() {
        // Separation distance, not axis overlap: a label off the box's corner leaves it open.
        val cornerOnly = listOf(GraphRect(0f, 150f, 60f, 200f))
        assertEquals(100f, requireNotNull(find(drawnBounds = cornerOnly)).centerX, 0.01f)
    }

    @Test
    fun fallsBackToTheTightSlotWhenNothingIsWideOpen() {
        // Same 6px-clearance curve, now across the WHOLE plot: no anchor clears the pass-1 bar, so the
        // permissive pass must still place the label rather than dropping it.
        val placement = requireNotNull(find(plot = SINGLE_BAND_PLOT, curveYsAt = { listOf(23f) }))
        assertEquals(
            "pass 2 keeps the original earliest-anchor preference",
            100f,
            placement.centerX,
            0.01f,
        )
    }

    @Test
    fun openClearanceScalesWithTheTextHeight() {
        // The bar is one line height (13px here), not a fixed dp: 12px of air fails, 14px passes.
        val metrics = this.metrics
        assertEquals(13f, GraphEmptySpaceFinder.openClearanceFor(metrics, padPx = 4f), 0.001f)
        // A short label with the same pad is held to the pad multiple instead.
        val short = GraphEmptySpaceFinder.Metrics(width = 60f, ascent = -6f, descent = 2f)
        assertEquals(12f, GraphEmptySpaceFinder.openClearanceFor(short, padPx = 4f), 0.001f)
    }

    @Test
    fun aZeroOpenClearanceRestoresTheSingleSweep() {
        // Escape hatch for callers that want pure first-fit: the tight left anchor wins again.
        val placement = requireNotNull(
            GraphEmptySpaceFinder.find(
                plot = SINGLE_BAND_PLOT,
                drawnBounds = emptyList(),
                curveYsAt = TIGHT_UNDER_THE_FIRST_ANCHOR,
                metrics = metrics,
                padPx = 4f,
                xFractions = listOf(0.25f, 0.75f),
                openClearancePx = 0f,
            ),
        )
        assertEquals(100f, placement.centerX, 0.01f)
    }

    @Test
    fun aVetoBoundBlocksTheBoxItOverlaps() {
        // The NOW line's case: a thin vertical through anchor 0.25's only band. Nothing else objects —
        // the curve is metres away — so only the veto can move the label.
        val nowLine = listOf(GraphRect(98f, 0f, 102f, 21f))
        val placement = requireNotNull(
            findWithVeto(plot = SINGLE_BAND_PLOT, vetoBounds = nowLine),
        )
        assertEquals(300f, placement.centerX, 0.01f)
    }

    @Test
    fun aVetoBoundRepelsNothing() {
        // The whole point of the separate list: 3px clear of the veto is fine, where the same gap to a
        // drawnBound would fail pass 1. Box at anchor 0.25 spans 70..130, so a veto ending at 67 is 3px
        // away — and must still be chosen over the later anchor.
        val nowLine = listOf(GraphRect(63f, 0f, 67f, 21f))
        val placement = requireNotNull(
            findWithVeto(plot = SINGLE_BAND_PLOT, vetoBounds = nowLine),
        )
        assertEquals(
            "a veto bound must block overlap without pushing the label away",
            100f,
            placement.centerX,
            0.01f,
        )
    }

    @Test
    fun aVetoBoundIsClearedVerticallyNotOnlyHorizontally() {
        // The NOW line spans 60% of the plot height, centred — a label in the top band clears it at any
        // x. A full-height veto rect would wrongly evict the label sideways.
        val centreBand = listOf(GraphRect(98f, 60f, 102f, 140f))
        val placement = requireNotNull(findWithVeto(vetoBounds = centreBand))
        assertEquals("the top band clears the centred line", 100f, placement.centerX, 0.01f)
        assertTrue("expected a band above the line, got top=${placement.box.top}", placement.box.bottom <= 60f)
    }

    @Test
    fun baselineAndBoxAgree() {
        val slot = requireNotNull(find())
        assertEquals(slot.box.top - metrics.ascent, slot.baselineY, 0.001f)
        assertEquals(metrics.width, slot.box.right - slot.box.left, 0.001f)
        assertEquals(metrics.height, slot.box.bottom - slot.box.top, 0.001f)
        assertEquals((slot.box.left + slot.box.right) / 2f, slot.centerX, 0.001f)
    }

    /**
     * The nav-arrow case (2026-08-18): a veto band at the left edge, mid-height. The label must step
     * out of the band **without abandoning the left edge** — the arrow costs it a vertical slot, not
     * the anchor. If this ever asserts only "no overlap", it stops distinguishing the fix from
     * registering the arrow as a repelling `drawnBounds`, which would drive the label to the far side.
     */
    @Test
    fun aNavArrowVetoMovesTheLabelVerticallyNotToTheOppositeEdge() {
        val tallPlot = GraphRect(0f, 0f, 400f, 200f)
        // 36dp-ish of arrow at the left edge, centred vertically like the real chevron.
        val leftArrow = GraphRect(0f, 80f, 60f, 120f)

        val placement =
            requireNotNull(
                GraphEmptySpaceFinder.find(
                    plot = tallPlot,
                    drawnBounds = emptyList(),
                    curveYsAt = { emptyList() },
                    metrics = metrics,
                    padPx = 4f,
                    xFractions = listOf(0.08f, 0.92f),
                    vetoBounds = listOf(leftArrow),
                ),
            ) { "expected a slot somewhere on an otherwise empty plot" }

        assertTrue(
            "label must not be drawn across the arrow. box=${placement.box} arrow=$leftArrow",
            placement.box.right <= leftArrow.left ||
                placement.box.left >= leftArrow.right ||
                placement.box.bottom <= leftArrow.top ||
                placement.box.top >= leftArrow.bottom,
        )
        assertTrue(
            "veto must not repel: the label should still hug the left anchor, got centerX=" +
                "${placement.centerX} on a ${tallPlot.width}px plot",
            placement.centerX < tallPlot.width / 2f,
        )
    }
}
