package com.weatherwidget.widget

import com.weatherwidget.shared.graph.GraphLabelPlacementUtils
import com.weatherwidget.shared.graph.TemperatureRole
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
    fun `cloud cover labels do not allow icon overlap even for low preferred below placements`() {
        assertTrue(
            !CloudCoverGraphRenderer.shouldAllowIconOverlap(
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
