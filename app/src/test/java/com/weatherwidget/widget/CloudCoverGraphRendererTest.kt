package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudCoverGraphRendererTest {

    @Test
    fun `vertical scale uses visible max plus headroom for moderate cloud peaks`() {
        val result = CloudCoverGraphRenderer.computeVerticalScale(listOf(12f, 69f, 44f))

        assertEquals(69f, result.visibleMax)
        assertEquals(85f, result.topScale)
    }

    @Test
    fun `vertical scale respects minimum top scale floor for lower cloud days`() {
        val result = CloudCoverGraphRenderer.computeVerticalScale(listOf(12f, 40f, 44f))

        assertEquals(44f, result.visibleMax)
        assertEquals(85f, result.topScale)
    }

    @Test
    fun `vertical scale clamps near overcast peaks to one hundred`() {
        val result = CloudCoverGraphRenderer.computeVerticalScale(listOf(92f, 97f, 99f))

        assertEquals(99f, result.visibleMax)
        assertEquals(100f, result.topScale)
    }

    @Test
    fun `dynamic scaling maps a moderate cloud value higher than fixed hundred scale`() {
        val dynamicY = CloudCoverGraphRenderer.mapCloudCoverToY(
            cloudCover = 69f,
            graphBottom = 100f,
            graphHeight = 100f,
            topScale = 85f,
        )
        val fixedScaleY = 100f - 100f * (69f / 100f)

        assertTrue(dynamicY < fixedScaleY)
    }

    @Test
    fun `zero cloud remains anchored to graph bottom`() {
        val y = CloudCoverGraphRenderer.mapCloudCoverToY(
            cloudCover = 0f,
            graphBottom = 100f,
            graphHeight = 100f,
            topScale = 85f,
        )

        assertEquals(100f, y)
    }

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

    @Test
    fun `low preferred below labels allow small bottom overflow`() {
        assertTrue(
            CloudCoverGraphRenderer.shouldAllowBottomOverflow(
                cloudPct = 10,
                placeAbove = false,
                isFallbackAttempt = false,
            ),
        )
    }

    @Test
    fun `fallback and above labels do not allow bottom overflow`() {
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowBottomOverflow(
                cloudPct = 10,
                placeAbove = true,
                isFallbackAttempt = false,
            ),
        )
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowBottomOverflow(
                cloudPct = 10,
                placeAbove = false,
                isFallbackAttempt = true,
            ),
        )
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowBottomOverflow(
                cloudPct = 60,
                placeAbove = false,
                isFallbackAttempt = false,
            ),
        )
    }

    @Test
    fun `low preferred below labels allow icon overlap`() {
        assertTrue(
            CloudCoverGraphRenderer.shouldAllowIconOverlap(
                cloudPct = 10,
                placeAbove = false,
                isFallbackAttempt = false,
            ),
        )
    }

    @Test
    fun `fallback above and higher labels do not allow icon overlap`() {
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowIconOverlap(
                cloudPct = 10,
                placeAbove = true,
                isFallbackAttempt = false,
            ),
        )
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowIconOverlap(
                cloudPct = 10,
                placeAbove = false,
                isFallbackAttempt = true,
            ),
        )
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowIconOverlap(
                cloudPct = 60,
                placeAbove = false,
                isFallbackAttempt = false,
            ),
        )
    }
}
