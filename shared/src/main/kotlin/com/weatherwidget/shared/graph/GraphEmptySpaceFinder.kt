package com.weatherwidget.shared.graph

/**
 * Finds a clear rectangle in a graph's plot area for a free-floating annotation.
 *
 * Extracted from [ForecastDeltaLabel] once a second label ([DominantStationLabel]) needed the same
 * search. Both keep their own gates, text and color; only "where is there room?" lives here. Pure and
 * platform-free, so Android's Canvas and desktop's Compose renderers pick identical slots.
 *
 * The search is anchor-major: for each x-fraction in order, step candidate boxes down a vertical band
 * and keep the one with the most room around the curve. The FIRST anchor that yields any legal box
 * wins — later anchors are never consulted, so the caller's [xFractions] order is a hard preference,
 * not a tiebreak. That is what lets two labels claim opposite ends of an empty plot by passing
 * mirrored lists.
 */
object GraphEmptySpaceFinder {

    /** Vertical search resolution: candidate top positions stepped through the plot band. */
    const val VERTICAL_STEPS = 6

    /** Curve clearance sampling across the candidate box width. */
    private const val CURVE_SAMPLES = 5

    /** Text metrics from the platform's paint. [ascent] is negative, [descent] positive. */
    data class Metrics(val width: Float, val ascent: Float, val descent: Float) {
        val height: Float get() = descent - ascent
    }

    /**
     * A found slot, in both coordinate conventions: [centerX] + [baselineY] for a center-aligned
     * Android `Canvas.drawText`, and [box] (top-left) for Compose `drawText(topLeft = box.topLeft)`.
     */
    data class Slot(
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
    )

    /**
     * Returns the emptiest legal slot, or null when the text cannot fit clear of everything.
     *
     * [plot] is the curve-drawing area (exclude the footer). [drawnBounds] are obstacles already on the
     * canvas (labels, icons, fetch-dot).
     *
     * [curveYsAt] returns the y of **every line drawn at that x** — plural, and that is load-bearing.
     * The hourly graph draws the forecast line, the observed line and the ghost line over overlapping x
     * ranges, so a sampler that answers with only one of them reports a huge clearance for a box sitting
     * squarely on another. (That is exactly how the dominant-station label first shipped on top of the
     * forecast dashes.) Return an empty list where nothing is drawn.
     *
     * [padPx] is the minimum clearance kept on all sides.
     */
    fun find(
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYsAt: (Float) -> List<Float>,
        metrics: Metrics,
        padPx: Float,
        xFractions: List<Float>,
        verticalSteps: Int = VERTICAL_STEPS,
    ): Slot? {
        if (metrics.width <= 0f || metrics.height <= 0f) return null
        if (metrics.width + 2f * padPx > plot.width) return null

        val w = metrics.width
        val h = metrics.height

        val minTop = plot.top + padPx
        val maxTop = plot.bottom - padPx - h
        if (maxTop < minTop) return null

        for (xf in xFractions) {
            val cx = (plot.left + xf * plot.width)
                .coerceIn(plot.left + w / 2f + padPx, plot.right - w / 2f - padPx)
            val left = cx - w / 2f
            val right = cx + w / 2f

            var best: Slot? = null
            var bestClearance = -1f
            for (s in 0..verticalSteps) {
                val top = minTop + (maxTop - minTop) * (s.toFloat() / verticalSteps)
                val box = GraphRect(left, top, right, top + h)
                if (drawnBounds.any { it.intersects(box) }) continue
                val clearance = curveClearance(box, curveYsAt) ?: continue // null = a curve intrudes
                if (clearance < padPx) continue
                if (clearance > bestClearance) {
                    bestClearance = clearance
                    best = Slot(
                        centerX = cx,
                        baselineY = top - metrics.ascent, // ascent is negative → baseline sits below top
                        box = box,
                    )
                }
            }
            if (best != null) return best // prefer the earliest x-anchor that has room
        }
        return null
    }

    /**
     * Minimum vertical distance from [box] to the NEAREST drawn curve across the box's x-range, or null
     * when any curve passes THROUGH the box (no clearance). A box sitting entirely above or below a
     * curve still reports its gap, so the emptiest band wins; taking the minimum across curves means one
     * crowded line vetoes the slot no matter how much room the others leave.
     */
    private fun curveClearance(box: GraphRect, curveYsAt: (Float) -> List<Float>): Float? {
        var minGap = Float.MAX_VALUE
        for (i in 0..CURVE_SAMPLES) {
            val x = box.left + (box.right - box.left) * (i.toFloat() / CURVE_SAMPLES)
            for (y in curveYsAt(x)) {
                if (y in box.top..box.bottom) return null
                val gap = if (y < box.top) box.top - y else y - box.bottom
                if (gap < minGap) minGap = gap
            }
        }
        return if (minGap == Float.MAX_VALUE) Float.MAX_VALUE else minGap
    }
}
