package com.weatherwidget.shared.graph

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, platform-free placement for the temperature value(s) drawn **on the ghost line** of the
 * zoomed-in hourly temperature graph. The ghost line is the forecast curve shifted by the
 * currently-observed delta (`expected = forecast + appliedDelta`) — "what we'd expect the real
 * temperature to be" — drawn only in the future region (right of the fetch dot). This object owns
 * where those labels sit, what they read, and when they show, so Android and desktop stay
 * consistent. One label is placed per eligible hour mark that has room, so the line may carry
 * several labels at once.
 *
 * - **Value**: the ghost line's own temperature at the chosen hour, to **one decimal** (e.g. `69.4°`).
 * - **Anchor**: snapped to an hour point so it reads against the footer hour labels ("at 6 PM → 69.4°").
 *   Only hour points on the **right half** of the plot are considered; hours whose footer label is
 *   shown are preferred so the temperature sits above a visible hour tick.
 * - **Visibility**: the narrow / zoomed-in view only, gated by [MAX_HOURS_SPAN] (12 h — same as the
 *   fetch-dot age label). The caller additionally gates on the ghost line being drawn at all
 *   (now-indicator visible, meaningful delta, fetch dot present).
 * - **Placement**: hugs the line (small gap above, else below) and is drawn **only when clear** of
 *   every already-placed label and of the curve; returns null when the right half is crowded.
 *
 * Styling (faint, ghost-like) is applied by each platform's paint; this stays free of
 * android.graphics / Compose types and only computes geometry + text.
 */
object GhostLineLabel {
    /** Narrow-only gate; matches the fetch-dot age label ([FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN]). */
    const val MAX_HOURS_SPAN = FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN

    /** Only consider ghost-line hour points whose x falls in the right portion of the plot. */
    const val RIGHT_HALF_FRACTION = 0.5f

    /** Curve clearance sampling across the candidate box width. */
    private const val CURVE_SAMPLES = 5

    /** Text metrics from the platform's ghost-label paint. [ascent] is negative, [descent] positive. */
    data class Metrics(val width: Float, val ascent: Float, val descent: Float) {
        val height: Float get() = descent - ascent
    }

    /**
     * One ghost-line hour point offered as a possible anchor. [x]/[ghostY] are screen coordinates of
     * the ghost line at that hour, [expectedTemp] is its value (forecast + delta), and [hasHourLabel]
     * is true when this hour's footer label is shown (preferred so the temp sits over a visible tick).
     */
    data class Candidate(
        val x: Float,
        val ghostY: Float,
        val expectedTemp: Float,
        val hasHourLabel: Boolean,
    )

    /**
     * A placed label, in both coordinate conventions: [centerX] + [baselineY] for a center-aligned
     * Android `Canvas.drawText`, and [box] (top-left) for Compose `drawText(topLeft = box.topLeft)`.
     */
    data class Placement(
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
    )

    /** The ghost-line temperature, always to one decimal, e.g. `69.4°`, `0.0°`, `-1.2°`. */
    fun format(temp: Float): String {
        if (!temp.isFinite()) return "--°"
        val tenths = (temp * 10f).roundToInt() // round-half-up at the tenths place
        val sign = if (tenths < 0) "-" else ""
        val mag = abs(tenths)
        return "$sign${mag / 10}.${mag % 10}°"
    }

    /**
     * Resolves **every** ghost-line label to draw, left-to-right, or an empty list when none should
     * show (window too wide, no candidates, or no clear hour point on the right half). One label is
     * placed at each eligible hour mark that has room — the ghost line can carry several labels.
     *
     * [plot] is the curve-drawing area (exclude the footer). [drawnBounds] are obstacles already on
     * the canvas. [curveYAt] returns the visible curve's y at a given x, or null off-curve. [padPx]
     * is the edge clearance; [gapPx] is the gap kept between a label and the ghost line it hugs.
     *
     * Hours whose footer label is shown are preferred so each temp sits over a visible tick; if no
     * right-half hour carries a footer label we fall back to all right-half hours. Labels are placed
     * against a **running obstacle list** (seeded from [drawnBounds]), so each placed ghost label
     * becomes an obstacle for the next — they never stack on each other.
     */
    fun placeAll(
        candidates: List<Candidate>,
        spanHours: Long,
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYAt: (Float) -> Float?,
        metrics: Metrics,
        padPx: Float,
        gapPx: Float,
        maxSpanHours: Long = MAX_HOURS_SPAN,
    ): List<Placement> {
        if (spanHours > maxSpanHours) return emptyList()
        if (metrics.width <= 0f || metrics.height <= 0f) return emptyList()
        if (candidates.isEmpty()) return emptyList()
        if (metrics.width + 2f * padPx > plot.width) return emptyList()

        val rightCutoff = plot.left + RIGHT_HALF_FRACTION * plot.width
        val rightHalf = candidates.filter { it.x >= rightCutoff }
        if (rightHalf.isEmpty()) return emptyList()

        // Prefer hours whose footer label is shown so each temp sits over a visible tick; fall back to
        // any right-half hour only if none of them carry a footer label.
        val labeled = rightHalf.filter { it.hasHourLabel }
        val eligible = (if (labeled.isNotEmpty()) labeled else rightHalf).sortedBy { it.x }

        val obstacles = drawnBounds.toMutableList()
        val placed = mutableListOf<Placement>()
        for (c in eligible) {
            val p = tryPlaceAt(c, plot, obstacles, curveYAt, metrics, padPx, gapPx) ?: continue
            placed.add(p)
            obstacles.add(p.box) // later ghost labels avoid the ones already placed
        }
        return placed
    }

    /**
     * Best placement for a single hour's ghost label: hugs the line (just above, else just below),
     * skipping any box that leaves the plot, overlaps an [obstacles] rect, or has the curve passing
     * through it. Of the valid above/below options the one with more curve clearance wins. Null when
     * neither fits.
     */
    private fun tryPlaceAt(
        c: Candidate,
        plot: GraphRect,
        obstacles: List<GraphRect>,
        curveYAt: (Float) -> Float?,
        metrics: Metrics,
        padPx: Float,
        gapPx: Float,
    ): Placement? {
        val w = metrics.width
        val h = metrics.height
        val cx = c.x.coerceIn(plot.left + w / 2f + padPx, plot.right - w / 2f - padPx)
        val left = cx - w / 2f
        val right = cx + w / 2f
        var best: Placement? = null
        var bestClearance = -1f
        // Hug the line: try just above, then just below.
        for (above in booleanArrayOf(true, false)) {
            val top = if (above) c.ghostY - gapPx - h else c.ghostY + gapPx
            val box = GraphRect(left, top, right, top + h)
            if (box.top < plot.top + padPx || box.bottom > plot.bottom - padPx) continue
            if (obstacles.any { it.intersects(box) }) continue
            val clearance = curveClearance(box, curveYAt) ?: continue // null = curve intrudes
            if (clearance > bestClearance) {
                bestClearance = clearance
                best = Placement(
                    text = format(c.expectedTemp),
                    centerX = cx,
                    baselineY = top - metrics.ascent, // ascent is negative → baseline below top
                    box = box,
                )
            }
        }
        return best
    }

    /**
     * Minimum vertical distance from [box] to the visible curve across the box's x-range, or null when
     * the curve passes THROUGH the box (no clearance). Lets a box that sits entirely above or below the
     * curve report its gap so the emptiest hour wins.
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
