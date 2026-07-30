package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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

    // --- Forecast-line parity guard (plan 260721-forecast-line-smoothing-parity) --------------------
    // The temperature forecast line is drawn from RAW hourly values on both platforms; the cubic here
    // smooths the PATH, not the data, so the curve must pass through each forecast value. An earlier
    // desktop-only value-smoothing pass sagged non-peak nodes (e.g. 84 -> ~82.7 on a decline), which
    // put the current-temp dot on the wrong side of the line vs Android. These lock that out.

    /** The value (y) of the drawn cubic at parameter [t] within segment [i], as both platforms build it. */
    private fun curveValueInSegment(values: List<Float>, i: Int, t: Float): Float {
        val tan = CurveMath.computeTangents(values.indices.map { it.toFloat() to values[it] })
        val p0 = values[i]
        val p1 = values[i] + tan[i].second / 3f       // cp1 = point + tangent/3 (platform path builders)
        val p2 = values[i + 1] - tan[i + 1].second / 3f // cp2 = next  - nextTangent/3
        val p3 = values[i + 1]
        val u = 1f - t
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    }

    @Test
    fun `raw forecast curve passes through every node value (no smoothing sag)`() {
        // The real NWS decline that surfaced the bug: 6p..9p = 86, 84, 78, 74.
        val values = listOf(86f, 84f, 78f, 74f)
        // At each node the drawn curve equals the raw forecast value — the 84 at 7pm renders at 84,
        // NOT the ~82.7 the old value-smoothing produced.
        values.indices.forEach { node ->
            val onSegment = if (node < values.lastIndex) curveValueInSegment(values, node, 0f)
            else curveValueInSegment(values, node - 1, 1f)
            assertEquals("node $node must sit on its raw value", values[node], onSegment, 1e-3f)
        }
    }

    @Test
    fun `raw monotone decline does not overshoot the node band at any sample (wide-zoom safety)`() {
        // Raw + Catmull-Rom must not wiggle past the data between monotone nodes — the automated proxy
        // for the wide-zoom check. CurveMath's monotone-aware tangents guarantee this.
        val values = listOf(86f, 84f, 78f, 74f)
        val eps = 1e-3f
        for (seg in 0 until values.lastIndex) {
            val lo = minOf(values[seg], values[seg + 1]) - eps
            val hi = maxOf(values[seg], values[seg + 1]) + eps
            var t = 0f
            while (t <= 1f) {
                val v = curveValueInSegment(values, seg, t)
                assertTrue("seg $seg t=$t value $v below band [$lo,$hi]", v >= lo)
                assertTrue("seg $seg t=$t value $v above band [$lo,$hi]", v <= hi)
                t += 0.05f
            }
        }
    }
}
