package com.weatherwidget.shared.graph

import kotlin.math.abs

/**
 * The precise curve-intrusion primitive shared by every curve-avoidance pass in the temperature
 * label engine: the Y-extent to which a polyline ([curveIntrusionInLabel]) — or the union of two
 * polylines ([combinedCurveIntrusion]) — penetrates a label's bounding box.
 *
 * This is the *precise* variant: it interpolates each segment across the box's x-range, so a
 * sub-hourly observed point that only clips the box corner is still reported. The cheaper
 * free-floating label searches ([GraphEmptySpaceFinder], [GhostLineLabel]) sample a fixed number of
 * points and answer with a clearance score instead; the engine's curve-avoidance passes need the
 * exact extent, so they use this.
 */
data class CurveIntrusion(val minY: Float, val maxY: Float) {
    val isEmpty: Boolean get() = minY > maxY

    companion object {
        val NONE = CurveIntrusion(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

        fun merge(a: CurveIntrusion, b: CurveIntrusion): CurveIntrusion = when {
            a.isEmpty -> b
            b.isEmpty -> a
            else -> CurveIntrusion(minOf(a.minY, b.minY), maxOf(a.maxY, b.maxY))
        }
    }
}

private const val MIN_INTERPOLATION_SPAN = 0.0001f
private const val CURVE_AVOIDANCE_MARGIN_PX = 0.5f

/**
 * Y-extent (minY, maxY) of the polyline [points] inside [bounds] (expanded by the avoidance margin),
 * or [CurveIntrusion.NONE] when the line does not cross the box.
 */
internal fun curveIntrusionInLabel(
    points: List<Pair<Float, Float>>,
    bounds: GraphRect,
): CurveIntrusion {
    if (points.size < 2) return CurveIntrusion.NONE
    val left = bounds.left - CURVE_AVOIDANCE_MARGIN_PX
    val right = bounds.right + CURVE_AVOIDANCE_MARGIN_PX
    val top = bounds.top - CURVE_AVOIDANCE_MARGIN_PX
    val bottom = bounds.bottom + CURVE_AVOIDANCE_MARGIN_PX
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val segMinX = minOf(a.first, b.first)
        val segMaxX = maxOf(a.first, b.first)
        if (segMaxX < left || segMinX > right) continue
        val span = (b.first - a.first)
        val ySegMin: Float
        val ySegMax: Float
        if (abs(span) < MIN_INTERPOLATION_SPAN) {
            ySegMin = a.second
            ySegMax = a.second
        } else {
            val xL = maxOf(segMinX, left)
            val xR = minOf(segMaxX, right)
            val tL = ((xL - a.first) / span).coerceIn(0f, 1f)
            val tR = ((xR - a.first) / span).coerceIn(0f, 1f)
            val yL = a.second + (b.second - a.second) * tL
            val yR = a.second + (b.second - a.second) * tR
            ySegMin = minOf(yL, yR)
            ySegMax = maxOf(yL, yR)
        }
        if (ySegMax < top || ySegMin > bottom) continue
        val clipMin = maxOf(ySegMin, top)
        val clipMax = minOf(ySegMax, bottom)
        if (clipMin < minY) minY = clipMin
        if (clipMax > maxY) maxY = clipMax
    }
    return CurveIntrusion(minY, maxY)
}

/**
 * Y-extent of the polyline [points] across the x-span [left]..[right], interpolating every segment
 * that crosses the span.
 *
 * Distinct from [curveIntrusionInLabel] in two ways that matter: it is not clipped in y, and it
 * reports a span the line merely *passes over* rather than one it penetrates. Callers use it to ask
 * "how high/low does the line actually run beneath this label?" — a question that must not be
 * answered by testing point membership alone, because a sampled line's vertices routinely straddle
 * a narrow label without landing inside it (the same trap behind the free-floating labels'
 * single-answer curve sampler).
 */
internal fun curveYExtentInXSpan(
    points: List<Pair<Float, Float>>,
    left: Float,
    right: Float,
): CurveIntrusion {
    if (points.isEmpty()) return CurveIntrusion.NONE
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    fun accept(y: Float) {
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    for ((px, py) in points) if (px in left..right) accept(py)
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val segMinX = minOf(a.first, b.first)
        val segMaxX = maxOf(a.first, b.first)
        if (segMaxX < left || segMinX > right) continue
        val span = b.first - a.first
        if (abs(span) < MIN_INTERPOLATION_SPAN) {
            accept(a.second)
            accept(b.second)
            continue
        }
        val tL = ((maxOf(segMinX, left) - a.first) / span).coerceIn(0f, 1f)
        val tR = ((minOf(segMaxX, right) - a.first) / span).coerceIn(0f, 1f)
        accept(a.second + (b.second - a.second) * tL)
        accept(a.second + (b.second - a.second) * tR)
    }
    return CurveIntrusion(minY, maxY)
}

/**
 * Union of the observed and forecast curves' intrusion into [bounds].
 */
internal fun combinedCurveIntrusion(
    actualVisiblePoints: List<Pair<Float, Float>>,
    forecastPoints: List<Pair<Float, Float>>,
    bounds: GraphRect,
): CurveIntrusion {
    val a = curveIntrusionInLabel(actualVisiblePoints, bounds)
    val f = curveIntrusionInLabel(forecastPoints, bounds)
    return CurveIntrusion.merge(a, f)
}
