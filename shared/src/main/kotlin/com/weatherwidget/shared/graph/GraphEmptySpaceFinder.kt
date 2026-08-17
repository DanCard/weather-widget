package com.weatherwidget.shared.graph

import kotlin.math.hypot

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
 *
 * **Two passes.** Anchor-major first-fit is only as good as the bar a box has to clear, and a bare
 * "legal" bar is very low: 2dp of curve clearance, with other drawn labels a binary intersects-or-not
 * test that gives a box one pixel from its neighbour the same score as one alone in an empty quadrant.
 * So `knuq 66.2° @ 8:35 pm` wedged itself between the `62°` start label and the delta label in the
 * bottom-left corner while the whole top-right of the plot stood empty (2026-08-16) — anchor 0.08 had
 * *a* legal box, so anchor 0.78 was never looked at. The search therefore runs the anchor list twice:
 * once demanding genuinely open space ([openClearanceFor], obstacles measured by distance), then, only
 * if that finds nothing anywhere, once under the original permissive rule. Preference order still
 * decides *within* a pass, so the mirrored [xFractions] contract survives.
 */
object GraphEmptySpaceFinder {

    /** Vertical search resolution: candidate top positions stepped through the plot band. */
    const val VERTICAL_STEPS = 6

    /** Curve clearance sampling across the candidate box width. */
    private const val CURVE_SAMPLES = 5

    /**
     * Pass-1 floor when the label's own line height is small: a label with 2dp of pad should not call
     * 3dp of air "wide open" just because it is a short string.
     */
    private const val OPEN_CLEARANCE_PAD_MULTIPLE = 3f

    /**
     * The pass-1 bar: one full line height of air on every side, never less than a few pads. Tied to
     * the text's own height rather than a dp constant because "roomy" scales with what is being drawn —
     * the same 15px gap is generous around a 9sp station id and cramped around a 30sp temperature.
     */
    fun openClearanceFor(
        metrics: Metrics,
        padPx: Float,
    ): Float = maxOf(padPx * OPEN_CLEARANCE_PAD_MULTIPLE, metrics.height)

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
     *
     * [vetoBounds] are obstacles that block a box they **overlap** but exert no repulsion — unlike
     * [drawnBounds], their distance never enters the pass-1 score. That is the difference between "do
     * not draw across this" and "keep clear of this". The NOW indicator is the case it exists for: a
     * dashed hairline spanning 60% of the plot height that a label must not be drawn over, but that a
     * label may sit right beside. Scoring its distance would push labels a full line height off it and
     * so refuse the narrow strip of plot to the right of NOW — which is exactly where the room is.
     *
     * [openClearancePx] is the pass-1 bar (see the class doc). Pass it explicitly to tune how picky the
     * first sweep is; pass `0f` to collapse the two passes into the original single permissive sweep.
     */
    fun find(
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYsAt: (Float) -> List<Float>,
        metrics: Metrics,
        padPx: Float,
        xFractions: List<Float>,
        verticalSteps: Int = VERTICAL_STEPS,
        openClearancePx: Float = openClearanceFor(metrics, padPx),
        vetoBounds: List<GraphRect> = emptyList(),
    ): Slot? {
        if (metrics.width <= 0f || metrics.height <= 0f) return null
        if (metrics.width + 2f * padPx > plot.width) return null

        val w = metrics.width
        val h = metrics.height

        val minTop = plot.top + padPx
        val maxTop = plot.bottom - padPx - h
        if (maxTop < minTop) return null

        /**
         * One sweep of the anchor list. [minClearance] is the bar a box must clear;
         * [countObstacleDistance] folds the distance to the nearest already-drawn label into the score
         * instead of only vetoing overlaps, which is what separates "wide open" from "technically legal".
         */
        fun sweep(
            minClearance: Float,
            countObstacleDistance: Boolean,
        ): Slot? {
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
                    if (vetoBounds.any { it.intersects(box) }) continue
                    val curve = curveClearance(box, curveYsAt) ?: continue // null = a curve intrudes
                    val clearance =
                        if (countObstacleDistance) {
                            minOf(curve, obstacleClearance(box, drawnBounds))
                        } else {
                            curve
                        }
                    if (clearance < minClearance) continue
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

        // Wide-open space anywhere on the plot beats a tight-but-legal slot at a preferred anchor.
        return sweep(minClearance = openClearancePx, countObstacleDistance = true)
            ?: sweep(minClearance = padPx, countObstacleDistance = false)
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

    /**
     * Distance from [box] to the nearest already-drawn obstacle — the rectangles' separation distance,
     * so a label sitting diagonally away scores as far rather than as touching. [Float.MAX_VALUE] when
     * nothing else is drawn; 0f for an overlap, though callers veto those before asking.
     *
     * Exists because intersects-or-not gives no gradient: without this, a box one pixel from the delta
     * label and a box alone in an empty quadrant were indistinguishable, which is how the two
     * free-floating labels ended up shoulder-to-shoulder despite their mirrored anchor lists.
     */
    private fun obstacleClearance(
        box: GraphRect,
        drawnBounds: List<GraphRect>,
    ): Float {
        var minGap = Float.MAX_VALUE
        for (other in drawnBounds) {
            val dx = maxOf(other.left - box.right, box.left - other.right, 0f)
            val dy = maxOf(other.top - box.bottom, box.top - other.bottom, 0f)
            val gap = if (dx == 0f) dy else if (dy == 0f) dx else hypot(dx, dy)
            if (gap < minGap) minGap = gap
        }
        return minGap
    }
}
