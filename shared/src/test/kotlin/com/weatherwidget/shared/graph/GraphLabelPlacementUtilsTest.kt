package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLabelPlacementUtilsTest {

    @Test
    fun `dense filter leaves low density candidate list unchanged`() {
        val labelSignal = listOf(10, 40, 20, 70, 35)
        val candidates = listOf(0, 1, 3, 4)

        val result = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 3,
            globalMinIdx = 0,
            maxCandidates = 5,
            diffThresholds = listOf(8, 12, 16),
            valueFunction = { it },
            logTag = "CloudCoverGraph",
        )

        assertEquals(candidates, result)
    }

    @Test
    fun `dense filter prefers peak over nearby edge label`() {
        val labelSignal = listOf(55, 40, 18, 10, 22, 30, 35, 28, 35, 34, 37, 45, 33, 26, 22, 20, 18, 19, 25, 30, 35, 39, 43, 38, 34)
        val candidates = listOf(0, 3, 8, 9, 11, 17, 22, 24)

        val result = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 0,
            globalMinIdx = 3,
            maxCandidates = 5,
            diffThresholds = listOf(8, 12, 16),
            valueFunction = { it },
            logTag = "CloudCoverGraph",
        )

        assertTrue(22 in result)
        assertTrue(24 !in result)
        assertTrue(result.size <= 5)
    }

    @Test
    fun `dense filter prefers peak over nearby valley`() {
        val labelSignal = listOf(55, 40, 18, 10, 22, 30, 35, 28, 35, 34, 37, 45, 33, 26, 22, 20, 18, 19, 25, 30, 35, 39, 43, 38, 34)
        val candidates = listOf(0, 3, 8, 9, 11, 17, 22, 24)

        val result = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 0,
            globalMinIdx = 3,
            maxCandidates = 5,
            diffThresholds = listOf(8, 12, 16),
            valueFunction = { it },
            logTag = "CloudCoverGraph",
        )

        assertTrue(11 in result)
        assertTrue(9 !in result)
    }

    @Test
    fun `dense filter keeps global extrema protected`() {
        val labelSignal = listOf(10, 12, 15, 18, 21, 50, 55, 60)
        val candidates = labelSignal.indices.toList()

        val result = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 7,
            globalMinIdx = 0,
            maxCandidates = 5,
            diffThresholds = listOf(8, 12, 16),
            valueFunction = { it },
            logTag = "CloudCoverGraph",
        )

        assertTrue(0 in result)
        assertTrue(7 in result)
    }

    @Test
    fun `non-absorbing anchor does not declutter a nearby similar-value candidate`() {
        // idx2 (valley, 63) sits within the window and declutter threshold of the immovable anchor
        // idx4 (66). It models a forecast valley next to an actual-series anchor that displays a
        // different value: the anchor must stay drawn but must NOT remove the forecast valley.
        val labelSignal = listOf(80, 66, 63, 64, 66, 50)
        val candidates = listOf(0, 2, 4, 5)

        // Default: the immovable anchor idx4 absorbs idx2 (the cross-series bug).
        val absorbed = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 0,
            globalMinIdx = 5,
            maxCandidates = 6,
            diffThresholds = listOf(3, 4, 5),
            valueFunction = { it },
            logTag = "TempLabelResolver",
            immovableIndices = setOf(0, 4, 5),
        )
        assertTrue("control: idx2 should be absorbed by idx4. result=$absorbed", 2 !in absorbed)
        assertTrue(4 in absorbed)

        // With idx4 marked non-absorbing, idx2 survives while idx4 stays retained.
        val retained = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 0,
            globalMinIdx = 5,
            maxCandidates = 6,
            diffThresholds = listOf(3, 4, 5),
            valueFunction = { it },
            logTag = "TempLabelResolver",
            immovableIndices = setOf(0, 4, 5),
            nonAbsorbingAnchors = setOf(4),
        )
        assertTrue("idx2 must survive when idx4 is non-absorbing. result=$retained", 2 in retained)
        assertTrue("idx4 must remain retained. result=$retained", 4 in retained)
    }

    @Test
    fun `left edge label is suppressed when nearby candidate has a similar value`() {
        val labelSignal = listOf(25, 18, 23, 22, 35, 53, 19, 43)
        val candidates = listOf(0, 2, 5, 6, 7)

        val result = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 5,
            globalMinIdx = 2,
            valueFunction = { it },
        )

        assertTrue(result)
    }

    @Test
    fun `left edge label is kept when it is the global max`() {
        val labelSignal = listOf(55, 40, 10, 22, 35, 43)
        val candidates = listOf(0, 2, 5)

        val result = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = 0,
            globalMinIdx = 2,
            valueFunction = { it },
        )

        assertTrue(!result)
    }

    @Test
    fun `preferred label gap uses two dp above and one dp below`() {
        val gap = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)

        assertEquals(2f, gap.aboveDp)
        assertEquals(1f, gap.belowDp)
    }

    @Test
    fun `fallback label gap uses two dp above and four dp below`() {
        val gap = GraphLabelPlacementUtils.getLabelGapDp(isFallback = true)

        assertEquals(2f, gap.aboveDp)
        assertEquals(4f, gap.belowDp)
    }

    @Test
    fun `below placement keeps glyph box below curve by requested gap`() {
        val placement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
            pointY = 100f,
            placeAbove = false,
            gapPx = 2f,
            textAscent = -10f,
            textDescent = 2f,
        )

        assertEquals(112f, placement.baselineY)
        assertEquals(102f, placement.top)
        assertEquals(114f, placement.bottom)
    }
}
