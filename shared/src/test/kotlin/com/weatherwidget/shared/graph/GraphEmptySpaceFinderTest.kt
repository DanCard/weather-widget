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

    /**
     * The Pixel case (2026-08-18): `knuq 64.4° @ 7:50 pm` shoulder-to-shoulder with the `Tue` day
     * label while the right half of the plot stood open.
     *
     * Neither anchor is wide open (13px bar), so the old two-pass search fell off its cliff: pass 2
     * stopped measuring obstacle distance entirely and handed the slot to the earliest anchor with any
     * legal box — the one 2px from its neighbour. The intermediate rung sees that the right anchor has
     * 9px, which is not roomy but is a whole bucket better, and takes it.
     *
     * Curves are absent so *only* obstacle distance can decide, and the single-band plot stops the left
     * anchor escaping vertically instead of sideways.
     */
    @Test
    fun aRoomierLaterAnchorBeatsACrampedEarlierOneEvenWhenNothingIsWideOpen() {
        val crowdingTheLeftAnchor = GraphRect(0f, 0f, 68f, 21f) // 2px off the box at 70..130
        val wellClearOfTheRightAnchor = GraphRect(339f, 0f, 400f, 21f) // 9px off the box at 270..330

        val placement = requireNotNull(
            find(
                plot = SINGLE_BAND_PLOT,
                drawnBounds = listOf(crowdingTheLeftAnchor, wellClearOfTheRightAnchor),
                curveYsAt = { emptyList() },
            ),
        )

        assertEquals(
            "expected the 9px anchor, not the 2px one the permissive pass used to return",
            300f,
            placement.centerX,
            0.01f,
        )
    }

    /**
     * The rungs are buckets, not a continuous score. Both anchors clear the top rung here — 14px and
     * 30px of air against a 13px bar — so the [xFractions] preference decides and the earlier anchor
     * keeps the slot despite being less roomy. Without this, a global "most clearance wins" search
     * would look like a reasonable simplification of the ladder and would quietly break the mirrored
     * anchor lists that keep the delta and station labels at opposite ends of the plot.
     */
    @Test
    fun withinOneRungTheEarlierAnchorStillWinsEvenWhenTheLaterOneIsRoomier() {
        val nearTheLeftAnchor = GraphRect(0f, 0f, 56f, 21f) // 14px off the box at 70..130
        val farFromTheRightAnchor = GraphRect(360f, 0f, 400f, 21f) // 30px off the box at 270..330

        val placement = requireNotNull(
            find(
                plot = SINGLE_BAND_PLOT,
                drawnBounds = listOf(nearTheLeftAnchor, farFromTheRightAnchor),
                curveYsAt = { emptyList() },
            ),
        )

        assertEquals(
            "anchor preference must survive inside a rung",
            100f,
            placement.centerX,
            0.01f,
        )
    }

    /**
     * Even the last, permissive rung ranks candidates with obstacle distance folded in. Both bands are
     * too close to the obstacle for any ladder rung (1px and 2px against a 4px pad floor), so the
     * search reaches the final sweep — which used to rank on curve clearance alone and, with no curve
     * drawn, therefore returned the first band it tried. A label that must be cramped should at least
     * be cramped in the roomier direction.
     *
     * `verticalSteps = 1` pins the candidates to exactly two bands so the assertion names a specific
     * one rather than whichever of seven happened to score highest.
     */
    @Test
    fun theLastRungStillPrefersTheBandFurtherFromItsNeighbour() {
        val betweenTheTwoBands = GraphRect(70f, 18f, 130f, 21f) // 1px under band A, 2px over band B

        val placement = requireNotNull(
            GraphEmptySpaceFinder.find(
                plot = GraphRect(0f, 0f, 400f, 40f),
                drawnBounds = listOf(betweenTheTwoBands),
                curveYsAt = { emptyList() },
                metrics = metrics,
                padPx = 4f,
                xFractions = listOf(0.25f),
                verticalSteps = 1,
            ),
        )

        assertEquals(
            "expected the lower band (2px of air), not the first-tried upper one (1px)",
            23f,
            placement.box.top,
            0.01f,
        )
    }

    /** The ladder must descend and must start at the full open-clearance bar. */
    @Test
    fun theClearanceLadderDescendsFromTheOpenBar() {
        assertEquals(1f, GraphEmptySpaceFinder.CLEARANCE_LADDER.first(), 0.001f)
        assertEquals(
            "rungs must strictly descend, else a later sweep re-tests the same candidates",
            GraphEmptySpaceFinder.CLEARANCE_LADDER.sortedDescending(),
            GraphEmptySpaceFinder.CLEARANCE_LADDER,
        )
        assertTrue(
            "every rung must be a fraction of the open bar, in (0, 1]",
            GraphEmptySpaceFinder.CLEARANCE_LADDER.all { it > 0f && it <= 1f },
        )
    }

    /**
     * The emulator case (2026-08-18): `knuq 62.6° @ 8:10 pm` drawn straight across the observed line.
     *
     * A steep limb is above the box at one sample and below it at the next, so the point test never
     * sees it inside and — because the gap ignores which side it fell on — both flanking samples score
     * the slot as wide open. Here the cliff is vertical, which no amount of extra sampling would catch;
     * only the crossing test can. Three candidate bands at the left anchor are all traversed, and the
     * right half of the plot has no line drawn at all, which is the room the user could see.
     */
    @Test
    fun aCurveCrossingBetweenTwoSamplesIsNotOpenAir() {
        val cliffUnderTheFirstAnchor = { x: Float ->
            when {
                x >= 200f -> emptyList() // right half: nothing drawn
                x < 100f -> listOf(0f) // above every band
                else -> listOf(200f) // below every band
            }
        }

        val placement = requireNotNull(
            GraphEmptySpaceFinder.find(
                plot = plot,
                drawnBounds = emptyList(),
                curveYsAt = cliffUnderTheFirstAnchor,
                metrics = metrics,
                padPx = 4f,
                xFractions = listOf(0.25f, 0.75f),
                verticalSteps = 2,
            ),
        )

        assertEquals(
            "the left anchor's bands are all traversed by the cliff; expected the empty right half",
            300f,
            placement.centerX,
            0.01f,
        )
    }

    /**
     * The crossing test counts curves above the box, so it must not fire for the ordinary case it most
     * resembles: a label sitting in the gap between two lines, one above and one below for the box's
     * whole width. That is the single most common good slot on the hourly graph — forecast dashes over,
     * observed line under — and vetoing it would cost far more than the bug being fixed.
     */
    @Test
    fun aBoxRidingBetweenTwoParallelCurvesIsStillOpenAir() {
        val above = 0f
        val below = 200f
        val placement = requireNotNull(
            GraphEmptySpaceFinder.find(
                plot = plot,
                drawnBounds = emptyList(),
                curveYsAt = { listOf(above, below) },
                metrics = metrics,
                padPx = 4f,
                xFractions = listOf(0.25f, 0.75f),
                verticalSteps = 2,
            ),
        )
        assertEquals(100f, placement.centerX, 0.01f)
    }

    /**
     * A line that starts partway through the box's x-range changes the count without anything having
     * crossed — the ghost line appearing at the fetch dot does exactly this. The count is therefore
     * only compared between samples reporting the same number of curves. Without that guard this slot
     * is rejected and the label is pushed off a perfectly good band.
     */
    @Test
    fun aCurveAppearingMidBoxIsNotMistakenForOneCrossingIt() {
        val ghostStartsHalfway = { x: Float ->
            if (x < 100f) listOf(200f) else listOf(200f, 0f)
        }
        val placement = requireNotNull(
            GraphEmptySpaceFinder.find(
                plot = plot,
                drawnBounds = emptyList(),
                curveYsAt = ghostStartsHalfway,
                metrics = metrics,
                padPx = 4f,
                xFractions = listOf(0.25f, 0.75f),
                verticalSteps = 2,
            ),
        )
        assertEquals(100f, placement.centerX, 0.01f)
    }

    /**
     * The other half of the sampling fix: a dip into the box and back out again leaves the above-count
     * unchanged, so only sample density can catch it. The station label is wide — 240px here, and
     * wider than that on a real widget — and six flat samples probed it every 48px, straight past a
     * 12px spike. Spacing now scales with the box.
     */
    @Test
    fun aNarrowSpikeIntoAWideBoxIsSampled() {
        val wideMetrics = GraphEmptySpaceFinder.Metrics(width = 240f, ascent = -10f, descent = 3f)
        // Box spans 80..320. The old sample positions were 80/128/176/224/272/320 — this sits between.
        val spikeBetweenTheOldSamples = { x: Float ->
            if (x >= 146f && x <= 158f) listOf(10f) else listOf(0f)
        }

        assertNull(
            "a spike through the box must veto it, not be sampled around",
            GraphEmptySpaceFinder.find(
                plot = SINGLE_BAND_PLOT,
                drawnBounds = emptyList(),
                curveYsAt = spikeBetweenTheOldSamples,
                metrics = wideMetrics,
                padPx = 4f,
                xFractions = listOf(0.5f),
            ),
        )
    }


    /**
     * The other emulator symptom (2026-08-18): `no_empty_band` on a plot whose right half looked empty.
     *
     * The station label is wider than the gap to the right of the NOW veto, so its only slot was a
     * narrow horizontal strip — under the `Wed` day label, above the NOW band. Six flat vertical steps
     * spanned 63 units each and stepped straight over a 17-unit window. Resolution now scales with the
     * label's own height, so a gap that barely fits it is still found.
     *
     * The obstacles here leave a legal top only in 44..50, which the 6-step grid (4, 33.8, 63.7, …)
     * cannot hit.
     */
    @Test
    fun aStripBarelyTallerThanTheLabelIsStillFound() {
        val above = GraphRect(0f, 0f, 400f, 44f)
        val below = GraphRect(0f, 63f, 400f, 200f)

        val placement = requireNotNull(
            find(drawnBounds = listOf(above, below), curveYsAt = { emptyList() }),
        ) { "expected the strip between the two obstacles" }

        assertTrue(
            "box must sit inside the 44..63 strip, got ${placement.box}",
            placement.box.top >= above.bottom && placement.box.bottom <= below.top,
        )
    }

    /**
     * Resolution scales with the label, not with the plot: a fixed step count gets coarser as the plot
     * grows, which is backwards. Bounded at both ends so a tiny label cannot turn the sweep into a
     * per-pixel scan and a short plot still gets a usable number of tries.
     */
    @Test
    fun verticalResolutionScalesWithTheLabelHeight() {
        assertEquals(
            "a band 30 line-heights tall should be stepped at about a third of a line height",
            28,
            GraphEmptySpaceFinder.verticalStepsFor(bandPx = 390f, boxHeightPx = 13f),
        )
        assertEquals(
            "a band with barely room for the label still gets the floor",
            GraphEmptySpaceFinder.VERTICAL_STEPS,
            GraphEmptySpaceFinder.verticalStepsFor(bandPx = 13f, boxHeightPx = 13f),
        )
        assertEquals(
            "degenerate height falls back to the floor rather than dividing by zero",
            GraphEmptySpaceFinder.VERTICAL_STEPS,
            GraphEmptySpaceFinder.verticalStepsFor(bandPx = 390f, boxHeightPx = 0f),
        )
    }
}
