package com.weatherwidget.shared.graph

import kotlin.math.abs
import kotlin.math.round

/**
 * Pure, platform-free formatting + placement for the "+0.4 from yesterday" delta label drawn on the
 * zoomed-in hourly temperature graph. The number itself comes from
 * [com.weatherwidget.shared.actuals.YesterdayDeltaCalculator]; this object owns how it reads, what color
 * it is, when it shows, and where it sits — so Android and desktop stay pixel-consistent.
 *
 * - **Text**: signed, one decimal, e.g. `+0.4 from yesterday`, `-1.2 from yesterday`, `+0.0 from yesterday`.
 * - **Color**: the thermostat gradient ([TemperatureColorModel]) evaluated at the *current* temperature,
 *   so the label harmonizes with the curve color at "now".
 * - **Visibility**: the narrow AND the 24 h view, gated by [DELTA_LABEL_MAX_HOURS_SPAN] (25 h — admits a
 *   full-day window, excludes the 3-day view), and only when a delta exists. This is intentionally wider
 *   than the fetch-dot age gate ([FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN], 12 h): "is this fresh enough to
 *   show an age?" and "is this zoomed in enough for a yesterday comparison?" are different questions.
 * - **Position**: dropped into empty space — a vertical band in the plot, preferring the visual center,
 *   that clears both the temperature curve and every already-placed label/icon/fetch-dot.
 *
 * Each platform supplies its own measured [Metrics] (from its staleness paint) and a [curveYAt] sampler
 * of the *visible* curve, so this stays free of android.graphics / Compose types.
 */
object YesterdayDeltaLabel {
    const val SUFFIX = " from yesterday"
    const val COMPACT_CAPTION = "yest"

    /**
     * Show the label for windows up to this span: admits the narrow view and the 24 h view (span 24 h),
     * excludes the 3-day view (span 72 h). Deliberately wider than the 12 h fetch-dot age gate.
     */
    const val DELTA_LABEL_MAX_HOURS_SPAN = 25L

    /** Number of x-anchors tried, in order; the first that yields any clear band wins (center first). */
    val X_FRACTIONS = listOf(0.5f, 0.35f, 0.65f, 0.22f, 0.78f)

    /** Vertical search resolution: candidate top positions stepped through the plot band. */
    const val VERTICAL_STEPS = 6

    /** Curve clearance sampling across the candidate box width. */
    private const val CURVE_SAMPLES = 5

    fun format(delta: Float, useCelsius: Boolean): String {
        return formatValue(delta, useCelsius) + SUFFIX
    }

    /** Signed numeric portion, shared by the one-line hourly label and multi-line daily overlay. */
    fun formatValue(delta: Float, useCelsius: Boolean): String {
        // The delta is a temperature *difference* in °F: convert by scaling only (no −32 offset).
        val displayDelta = if (useCelsius) delta / 1.8f else delta
        val tenths = round(displayDelta * 10f).toInt() // round-half-up at the tenths place
        val sign = if (tenths >= 0) "+" else "-"
        val mag = abs(tenths)
        return "$sign${mag / 10}.${mag % 10}"
    }

    fun colorArgb(currentTemp: Float): Int = TemperatureColorModel.tempToColorArgb(currentTemp)

    /** Text metrics from the platform's staleness paint. [ascent] is negative, [descent] positive. */
    data class Metrics(val width: Float, val ascent: Float, val descent: Float) {
        val height: Float get() = descent - ascent
    }

    /**
     * A placed label, in both coordinate conventions: [centerX] + [baselineY] for a center-aligned
     * Android `Canvas.drawText`, and [box] (top-left) for Compose `drawText(topLeft = box.topLeft)`.
     */
    data class Placement(
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
        val colorArgb: Int,
    )

    /**
     * Resolves the label, or null when it should not be drawn (no delta / current temp, window too wide,
     * or no empty band fits). [plot] is the curve-drawing area (exclude the footer). [drawnBounds] are
     * obstacles already on the canvas (labels, icons, fetch-dot). [curveYAt] returns the visible curve's
     * y at a given x, or null if off-curve. [padPx] is the minimum clearance kept on all sides.
     */
    fun place(
        delta: Float?,
        currentTemp: Float?,
        spanHours: Long,
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYAt: (Float) -> Float?,
        metrics: Metrics,
        padPx: Float,
        useCelsius: Boolean,
        maxSpanHours: Long = DELTA_LABEL_MAX_HOURS_SPAN,
    ): Placement? {
        if (delta == null || currentTemp == null) return null
        if (spanHours > maxSpanHours) return null
        if (metrics.width <= 0f || metrics.height <= 0f) return null
        if (metrics.width + 2f * padPx > plot.width) return null

        val text = format(delta, useCelsius)
        val color = colorArgb(currentTemp)
        val w = metrics.width
        val h = metrics.height

        val minTop = plot.top + padPx
        val maxTop = plot.bottom - padPx - h
        if (maxTop < minTop) return null

        for (xf in X_FRACTIONS) {
            val cx = (plot.left + xf * plot.width)
                .coerceIn(plot.left + w / 2f + padPx, plot.right - w / 2f - padPx)
            val left = cx - w / 2f
            val right = cx + w / 2f

            var best: Placement? = null
            var bestClearance = -1f
            for (s in 0..VERTICAL_STEPS) {
                val top = minTop + (maxTop - minTop) * (s.toFloat() / VERTICAL_STEPS)
                val box = GraphRect(left, top, right, top + h)
                if (drawnBounds.any { it.intersects(box) }) continue
                val clearance = curveClearance(box, curveYAt) ?: continue // null = curve intrudes
                if (clearance < padPx) continue
                if (clearance > bestClearance) {
                    bestClearance = clearance
                    best = Placement(
                        text = text,
                        centerX = cx,
                        baselineY = top - metrics.ascent, // ascent is negative → baseline sits below top
                        box = box,
                        colorArgb = color,
                    )
                }
            }
            if (best != null) return best // prefer the earliest (most central) x-anchor that has room
        }
        return null
    }

    /**
     * Minimum vertical distance from [box] to the visible curve across the box's x-range, or null when
     * the curve passes THROUGH the box (no clearance). Lets a box that sits entirely above or below the
     * curve report its gap so the emptiest band wins.
     */
    private fun curveClearance(box: GraphRect, curveYAt: (Float) -> Float?): Float? {
        var minGap = Float.MAX_VALUE
        for (i in 0..CURVE_SAMPLES) {
            val x = box.left + (box.right - box.left) * (i.toFloat() / CURVE_SAMPLES)
            val y = curveYAt(x) ?: continue
            if (y in box.top..box.bottom) return null
            val gap = if (y < box.top) box.top - y else y - box.bottom
            if (gap < minGap) minGap = gap
        }
        return if (minGap == Float.MAX_VALUE) Float.MAX_VALUE else minGap
    }
}
