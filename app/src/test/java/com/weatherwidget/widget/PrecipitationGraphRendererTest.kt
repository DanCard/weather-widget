package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecipitationGraphRendererTest {
    @Test
    fun `computeEndLabelPlacement anchors label near right edge`() {
        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 100f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = emptyList(),
                nowBounds = null,
            )

        assertNotNull(placement)
        assertEquals(272f, placement!!.x, 0.001f)
        assertTrue(!placement.usedFallback)
    }

    @Test
    fun `computeEndLabelPlacement clamps preferred baseline to top safe bound`() {
        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 5f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = emptyList(),
                nowBounds = null,
            )

        assertNotNull(placement)
        assertEquals(22f, placement!!.baselineY, 0.001f)
    }

    @Test
    fun `computeEndLabelPlacement uses fallback when preferred overlaps`() {
        val preferredBounds =
            PrecipitationGraphRenderer.PlacementRect(
                left = 252f,
                top = 70f,
                right = 292f,
                bottom = 93f,
            )

        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 100f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = listOf(preferredBounds),
                nowBounds = null,
            )

        assertNotNull(placement)
        assertTrue(placement!!.usedFallback)
        assertEquals(114f, placement.baselineY, 0.001f)
    }

    @Test
    fun `computeEndLabelPlacement returns null when preferred and fallback overlap`() {
        val preferredBounds =
            PrecipitationGraphRenderer.PlacementRect(
                left = 252f,
                top = 76f,
                right = 292f,
                bottom = 96f,
            )
        val fallbackBounds =
            PrecipitationGraphRenderer.PlacementRect(
                left = 252f,
                top = 94f,
                right = 292f,
                bottom = 114f,
            )

        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 100f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = listOf(preferredBounds, fallbackBounds),
                nowBounds = null,
            )

        assertNull(placement)
    }
}
