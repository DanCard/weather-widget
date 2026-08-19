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
 * **A ladder of passes.** Anchor-major first-fit is only as good as the bar a box has to clear, and a bare
 * "legal" bar is very low: 2dp of curve clearance, with other drawn labels a binary intersects-or-not
 * test that gives a box one pixel from its neighbour the same score as one alone in an empty quadrant.
 * So `knuq 66.2° @ 8:35 pm` wedged itself between the `62°` start label and the delta label in the
 * bottom-left corner while the whole top-right of the plot stood empty (2026-08-16) — anchor 0.08 had
 * *a* legal box, so anchor 0.78 was never looked at. The search therefore runs the anchor list
 * repeatedly at a **descending clearance bar** ([CLEARANCE_LADDER]), starting from genuinely open
 * space ([openClearanceFor]) and giving ground a rung at a time.
 *
 * The first version of that fix had only two rungs — wide open, then the original permissive rule —
 * so a plot where nothing anywhere is wide open fell straight off a cliff: obstacle distance stopped
 * being measured at all, and the earliest anchor with any legal box won however cramped it was. That
 * is how `knuq 64.4° @ 7:50 pm` came to sit shoulder-to-shoulder with the `Tue` day label on a Pixel
 * while the right half of the plot stood open (2026-08-18). The intermediate rungs exist so a merely
 * *good* slot still beats a bad one; the permissive sweep survives as the last rung, so a plot with
 * nowhere roomy still gets its label rather than losing it — and even there the candidate bands are
 * now *ranked* with obstacle distance included, so a label that must be cramped is at least cramped
 * against the curve rather than against its neighbour.
 *
 * Preference order still decides *within* a rung, so the mirrored [xFractions] contract survives: two
 * labels claim opposite ends of an empty plot exactly as before, and a later anchor only overtakes an
 * earlier one when it is roomier by a whole rung.
 */
object GraphEmptySpaceFinder {

    /**
     * Floor for the vertical search resolution — the number of candidate top positions stepped through
     * the plot band. Kept as a minimum rather than the answer: a fixed count gets *coarser* as the plot
     * gets taller, which is exactly backwards, since a taller plot is where there is most room to find.
     * See [verticalStepsFor].
     */
    const val VERTICAL_STEPS = 6

    /**
     * Candidate tops are stepped by about this fraction of the label's own height, so the search
     * resolution scales with what is being placed instead of with nothing at all.
     *
     * A third of a line height is fine enough to find a gap that only just fits the label. Six flat
     * steps was not: on the emulator (2026-08-18) the station label's only legal strip — under the
     * `Wed` day label and above the NOW veto band — was 17 units tall, and the search stepped 63 units
     * at a time straight over it, reporting `no_empty_band` on a plot whose whole right half looked
     * empty. The label is wider than the gap to the right of NOW, so that strip was the only slot it
     * had.
     */
    private const val VERTICAL_STEP_FRACTION_OF_BOX = 1f / 3f

    /** Ceiling on candidate tops per anchor, bounding the search on a very tall plot. */
    private const val MAX_VERTICAL_STEPS = 28

    /** Curve clearance sampling across the candidate box width: the floor for a narrow box. */
    private const val CURVE_SAMPLES = 5

    /**
     * Target spacing between curve samples. The old flat six samples meant a 400px-wide station label
     * probed its own footprint every 80px — coarse enough for a steep observed line to pass clean
     * between two of them.
     */
    private const val CURVE_SAMPLE_SPACING_PX = 12f

    /**
     * Ceiling on samples per box. The search evaluates a few hundred candidate boxes per render and
     * each sample interpolates every drawn series, so this is the knob that keeps a pure-math helper
     * from showing up in an 800ms bitmap rebuild.
     */
    private const val MAX_CURVE_SAMPLES = 32

    /**
     * Floor for the top rung when the label's own line height is small: a label with 2dp of pad should
     * not call 3dp of air "wide open" just because it is a short string.
     */
    private const val OPEN_CLEARANCE_PAD_MULTIPLE = 3f

    /**
     * The top rung of the ladder: one full line height of air on every side, never less than a few
     * pads. Tied to the text's own height rather than a dp constant because "roomy" scales with what is
     * being drawn — the same 15px gap is generous around a 9sp station id and cramped around a 30sp
     * temperature.
     */
    fun openClearanceFor(
        metrics: Metrics,
        padPx: Float,
    ): Float = maxOf(padPx * OPEN_CLEARANCE_PAD_MULTIPLE, metrics.height)

    /**
     * The clearance bar for each sweep, as a fraction of [openClearanceFor]. Descending: the search
     * asks for wide-open space first and settles for less only when no anchor can supply it.
     *
     * Coarse on purpose. The rungs are buckets, not a continuous score, because the [xFractions]
     * preference has to mean something: a later anchor should overtake an earlier one when it is
     * *substantially* roomier, not when it wins by a pixel. Three rungs put that threshold at roughly
     * a third of a line height, which is about where a gap stops reading as deliberate spacing and
     * starts reading as two labels that happen not to touch.
     *
     * Every rung is floored at `padPx` — below that the box is not legal anyway — and rungs that
     * collapse onto the floor are skipped rather than re-swept.
     */
    val CLEARANCE_LADDER = listOf(1f, 0.6f, 0.3f)

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
     * [drawnBounds], their distance never enters a candidate's score. That is the difference between "do
     * not draw across this" and "keep clear of this". The NOW indicator is the case it exists for: a
     * dashed hairline spanning 60% of the plot height that a label must not be drawn over, but that a
     * label may sit right beside. Scoring its distance would push labels a full line height off it and
     * so refuse the narrow strip of plot to the right of NOW — which is exactly where the room is.
     *
     * [openClearancePx] is the top rung of the clearance ladder (see the class doc); the rest of the
     * ladder is [CLEARANCE_LADDER] scaled by it. Pass it explicitly to tune how picky the search is;
     * pass `0f` to skip the ladder entirely and run the single permissive sweep. Note that even that
     * sweep now ranks candidates by obstacle distance — `0f` restores the original *eligibility* rule,
     * not the original blindness to how close the neighbours are.
     */
    fun find(
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYsAt: (Float) -> List<Float>,
        metrics: Metrics,
        padPx: Float,
        xFractions: List<Float>,
        verticalSteps: Int? = null,
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

        val steps = verticalSteps ?: verticalStepsFor(bandPx = maxTop - minTop, boxHeightPx = h)

        /**
         * One sweep of the anchor list at a given bar.
         *
         * [minCurveGap] and [minObstacleGap] are separate because the last rung needs them to differ:
         * it keeps the original permissive *eligibility* (clear the curve by a pad, do not overlap
         * anything) while still *ranking* candidates with obstacle distance folded in. Scoring is
         * always `min(curve, obstacle)` — the point of measuring the distance to the nearest drawn
         * label at all is that intersects-or-not gives no gradient, so a box one pixel from the delta
         * label and a box alone in an empty quadrant were indistinguishable.
         */
        fun sweep(
            minCurveGap: Float,
            minObstacleGap: Float,
        ): Slot? {
            for (xf in xFractions) {
                val cx = (plot.left + xf * plot.width)
                    .coerceIn(plot.left + w / 2f + padPx, plot.right - w / 2f - padPx)
                val left = cx - w / 2f
                val right = cx + w / 2f

                var best: Slot? = null
                var bestClearance = -1f
                for (s in 0..steps) {
                    val top = minTop + (maxTop - minTop) * (s.toFloat() / steps)
                    val box = GraphRect(left, top, right, top + h)
                    if (drawnBounds.any { it.intersects(box) }) continue
                    if (vetoBounds.any { it.intersects(box) }) continue
                    val curve = curveClearance(box, curveYsAt) ?: continue // null = a curve intrudes
                    if (curve < minCurveGap) continue
                    val obstacle = obstacleClearance(box, drawnBounds)
                    if (obstacle < minObstacleGap) continue
                    val clearance = minOf(curve, obstacle)
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

        // Roomier space anywhere on the plot beats a tighter slot at a preferred anchor — checked a
        // rung at a time, so the preference is only overruled by a materially better slot.
        if (openClearancePx > 0f) {
            for (fraction in CLEARANCE_LADDER) {
                val bar = (openClearancePx * fraction).coerceAtLeast(padPx)
                sweep(minCurveGap = bar, minObstacleGap = bar)?.let { return it }
                // Lower rungs would floor to the same bar and re-sweep identical candidates.
                if (bar <= padPx) break
            }
        }

        // Last rung: the original permissive rule for *eligibility*, obstacle-aware for *ranking*.
        return sweep(minCurveGap = padPx, minObstacleGap = 0f)
    }

    /**
     * Minimum vertical distance from [box] to the NEAREST drawn curve across the box's x-range, or null
     * when any curve passes THROUGH the box (no clearance). A box sitting entirely above or below a
     * curve still reports its gap, so the emptiest band wins; taking the minimum across curves means one
     * crowded line vetoes the slot no matter how much room the others leave.
     *
     * **Sampling a continuous line at discrete x is not enough on its own**, and that is the whole
     * reason for the crossing test below. `knuq 62.6° @ 8:10 pm` was drawn straight across the observed
     * line on the emulator (2026-08-18) with the box reporting healthy clearance: on a steep limb the
     * line was *above* the box at one sample and *below* it at the next, landing inside neither. The
     * gap is computed without regard to side, so both flanking samples scored as roomy and actively
     * vouched for the slot.
     *
     * So the box is rejected when the number of curves above it **changes** between adjacent samples:
     * a line that was above and no longer is went through the band in between, whatever its slope.
     * Counting rather than tracking identities keeps this working with the [curveYsAt] contract, whose
     * list is a set of ys at an x with no stable ordering — the observed line drops out past the fetch
     * dot and the ghost line appears there. The count is only compared when both samples report the
     * same number of curves, so a line starting or ending inside the box's x-range is not mistaken for
     * one crossing it.
     *
     * That leaves one gap: a line that dips into the box and back out entirely between two samples.
     * Hence the sample count scales with the box width ([CURVE_SAMPLE_SPACING_PX]) instead of the flat
     * six that a 400px-wide station label used to get.
     */
    private fun curveClearance(box: GraphRect, curveYsAt: (Float) -> List<Float>): Float? {
        var minGap = Float.MAX_VALUE
        val samples = curveSamplesFor(box.right - box.left)
        var previousAbove = -1
        var previousCount = -1
        for (i in 0..samples) {
            val x = box.left + (box.right - box.left) * (i.toFloat() / samples)
            val ys = curveYsAt(x)
            var above = 0
            for (y in ys) {
                if (y in box.top..box.bottom) return null
                if (y < box.top) {
                    above++
                    val gap = box.top - y
                    if (gap < minGap) minGap = gap
                } else {
                    val gap = y - box.bottom
                    if (gap < minGap) minGap = gap
                }
            }
            // Same lines in play, different number of them above us ⇒ one crossed the band.
            if (previousCount == ys.size && above != previousAbove) return null
            previousAbove = above
            previousCount = ys.size
        }
        return if (minGap == Float.MAX_VALUE) Float.MAX_VALUE else minGap
    }

    /**
     * Candidate top positions across a search band [bandPx] tall for a box [boxHeightPx] high — fine
     * enough to land inside a gap barely larger than the label itself.
     */
    fun verticalStepsFor(bandPx: Float, boxHeightPx: Float): Int =
        if (boxHeightPx <= 0f) {
            VERTICAL_STEPS
        } else {
            (bandPx / (boxHeightPx * VERTICAL_STEP_FRACTION_OF_BOX))
                .toInt()
                .coerceIn(VERTICAL_STEPS, MAX_VERTICAL_STEPS)
        }

    /** Sample intervals across a box [widthPx] wide, dense enough that a curve cannot dive through it. */
    private fun curveSamplesFor(widthPx: Float): Int =
        (widthPx / CURVE_SAMPLE_SPACING_PX)
            .toInt()
            .coerceIn(CURVE_SAMPLES, MAX_CURVE_SAMPLES)

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
