package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowIndicatorGeometryTest {

    // Identity dp converter so geometry asserts on raw pixel math.
    private val noScale: (Float) -> Float = { it }

    @Test
    fun `computeNowLine centers a 60 percent tall line`() {
        val line = NowIndicatorGeometry.computeNowLine(graphTop = 0f, graphHeight = 100f)
        // 0.6 * 100 = 60 tall, centered in 100 -> 20..80
        assertEquals(20f, line.lineTop, 0.001f)
        assertEquals(80f, line.lineBottom, 0.001f)
    }

    @Test
    fun `below-first placement sits beneath the line when nothing collides`() {
        val line = NowIndicatorGeometry.computeNowLine(0f, 100f)
        val p = NowIndicatorGeometry.computeNowLabel(
            nowX = 50f, graphTop = 0f, graphHeight = 100f,
            labelWidth = 30f, fontAscent = -12f, fontDescent = 0f,
            drawnBounds = emptyList(), dpToPx = noScale,
        )
        assertNotNull(p)
        // Below-first: the label box's top is at or below the line bottom.
        assertTrue("box.top ${p!!.box.top} should be >= lineBottom ${line.lineBottom}", p.box.top >= line.lineBottom)
    }

    @Test
    fun `collision below flips the label above the line`() {
        val line = NowIndicatorGeometry.computeNowLine(0f, 100f)
        // Block the below candidate (which lives just under lineBottom=80).
        val blockBelow = GraphRect(0f, line.lineBottom, 100f, line.lineBottom + 40f)
        val p = NowIndicatorGeometry.computeNowLabel(
            nowX = 50f, graphTop = 0f, graphHeight = 100f,
            labelWidth = 30f, fontAscent = -12f, fontDescent = 0f,
            drawnBounds = listOf(blockBelow), dpToPx = noScale,
        )
        assertNotNull(p)
        // Flipped above: the box bottom is at or above the line top.
        assertTrue("box.bottom ${p!!.box.bottom} should be <= lineTop ${line.lineTop}", p.box.bottom <= line.lineTop)
    }

    @Test
    fun `double collision suppresses the label`() {
        // A wall covering the whole graph (and beyond) collides with both candidates.
        val wall = GraphRect(-1000f, -1000f, 1000f, 1000f)
        val p = NowIndicatorGeometry.computeNowLabel(
            nowX = 50f, graphTop = 0f, graphHeight = 100f,
            labelWidth = 30f, fontAscent = -12f, fontDescent = 0f,
            drawnBounds = listOf(wall), dpToPx = noScale,
        )
        assertNull(p)
    }

    @Test
    fun `box is symmetric about nowX and consistent with baseline plus metrics`() {
        val p = NowIndicatorGeometry.computeNowLabel(
            nowX = 50f, graphTop = 0f, graphHeight = 100f,
            labelWidth = 30f, fontAscent = -12f, fontDescent = 4f,
            drawnBounds = emptyList(), dpToPx = noScale,
        )!!
        assertEquals(50f, p.centerX, 0.001f)
        assertEquals(35f, p.box.left, 0.001f)
        assertEquals(65f, p.box.right, 0.001f)
        assertEquals(p.baselineY - 12f, p.box.top, 0.001f)
        assertEquals(p.baselineY + 4f, p.box.bottom, 0.001f)
    }

    @Test
    fun `compose top-left convention yields box top equal to baseline minus height`() {
        // Desktop passes fontAscent = -height, fontDescent = 0; box.top is then the top-left y.
        val height = 18f
        val p = NowIndicatorGeometry.computeNowLabel(
            nowX = 50f, graphTop = 0f, graphHeight = 100f,
            labelWidth = 30f, fontAscent = -height, fontDescent = 0f,
            drawnBounds = emptyList(), dpToPx = noScale,
        )!!
        assertEquals(p.baselineY - height, p.box.top, 0.001f)
        assertEquals(height, p.box.height, 0.001f)
    }
}
