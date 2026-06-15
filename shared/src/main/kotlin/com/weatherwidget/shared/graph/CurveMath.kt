package com.weatherwidget.shared.graph

/**
 * Platform-free curve math shared by the Android widget (`GraphRenderUtils`) and the desktop
 * Compose graphs (`DesktopGraphUtils`). Only the geometry lives here — actual path construction
 * (android.graphics.Path vs Compose Path) stays in each platform renderer, since those APIs differ.
 *
 * Points are `(x, y)` pairs so this module pulls in neither android.graphics nor Compose.
 */
object CurveMath {
    /**
     * Monotone-aware Catmull-Rom tangents for a set of points.
     *
     * The tangents are tuned so the resulting cubic spline does not overshoot at peaks/valleys:
     * - endpoints use a one-sided half-difference,
     * - interior points zero out the Y tangent at plateaus or extrema (sign change), and
     * - non-uniform spacing (e.g. sub-hourly observations) is clamped to a max safe dx so the
     *   curve can't loop back on itself.
     *
     * Returned tangents are `(dx, dy)` pairs aligned 1:1 with [points].
     */
    fun computeTangents(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size < 2) return points.map { 0f to 0f }

        return points.indices.map { i ->
            when (i) {
                0 ->
                    Pair(
                        (points[1].first - points[0].first) * 0.5f,
                        (points[1].second - points[0].second) * 0.5f,
                    )

                points.size - 1 ->
                    Pair(
                        (points[i].first - points[i - 1].first) * 0.5f,
                        (points[i].second - points[i - 1].second) * 0.5f,
                    )

                else -> {
                    val dxPrev = points[i].first - points[i - 1].first
                    val dxNext = points[i + 1].first - points[i].first

                    val dx = (dxPrev + dxNext) * 0.5f
                    var dy = (points[i + 1].second - points[i - 1].second) * 0.5f

                    // Monotone-aware tangents: zero out Y tangent at a plateau or extremum.
                    val delta1 = points[i].second - points[i - 1].second
                    val delta2 = points[i + 1].second - points[i].second
                    if (delta1 == 0f || delta2 == 0f || (delta1 > 0 && delta2 < 0) || (delta1 < 0 && delta2 > 0)) {
                        dy = 0f
                    }

                    // For non-uniform spacing, prevent the tangent from overshooting the segment
                    // distance, which causes loopbacks and wild swoops.
                    val maxSafeDx = dxPrev.coerceAtMost(dxNext) * 1.5f
                    if (dx > maxSafeDx && maxSafeDx > 0) {
                        val scale = maxSafeDx / dx
                        Pair(maxSafeDx, dy * scale)
                    } else {
                        Pair(dx, dy)
                    }
                }
            }
        }
    }
}
