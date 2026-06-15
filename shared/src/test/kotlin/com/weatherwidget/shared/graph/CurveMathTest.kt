package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Test

class CurveMathTest {

    @Test
    fun `fewer than two points yields zero tangents`() {
        assertEquals(emptyList<Pair<Float, Float>>(), CurveMath.computeTangents(emptyList()))
        assertEquals(listOf(0f to 0f), CurveMath.computeTangents(listOf(1f to 2f)))
    }

    @Test
    fun `endpoints use one-sided half differences`() {
        val pts = listOf(0f to 0f, 10f to 20f, 20f to 10f)
        val t = CurveMath.computeTangents(pts)
        // first = half of (p1 - p0)
        assertEquals(5f, t.first().first, 1e-4f)
        assertEquals(10f, t.first().second, 1e-4f)
        // last = half of (pLast - pPrev)
        assertEquals(5f, t.last().first, 1e-4f)
        assertEquals(-5f, t.last().second, 1e-4f)
    }

    @Test
    fun `interior peak zeroes the y tangent to avoid overshoot`() {
        // middle point is a peak (up then down) -> dy forced to 0
        val pts = listOf(0f to 0f, 10f to 30f, 20f to 0f)
        val t = CurveMath.computeTangents(pts)
        assertEquals(0f, t[1].second, 1e-4f)
    }

    @Test
    fun `monotone interior keeps a nonzero y tangent`() {
        val pts = listOf(0f to 0f, 10f to 10f, 20f to 30f)
        val t = CurveMath.computeTangents(pts)
        assertEquals(15f, t[1].second, 1e-4f) // (30 - 0)/2
    }

    @Test
    fun `wide gap after a tight gap clamps the x tangent to max safe dx`() {
        // dxPrev = 1, dxNext = 100 -> dx = 50.5, maxSafeDx = min(1,100)*1.5 = 1.5
        val pts = listOf(0f to 0f, 1f to 10f, 101f to 20f)
        val t = CurveMath.computeTangents(pts)
        assertEquals(1.5f, t[1].first, 1e-4f)
    }
}
