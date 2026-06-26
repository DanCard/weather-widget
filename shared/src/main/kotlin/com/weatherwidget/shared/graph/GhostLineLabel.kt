package com.weatherwidget.shared.graph

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, platform-free placement for the single temperature value drawn **on the ghost line** of the
 * zoomed-in hourly temperature graph. The ghost line is the forecast curve shifted by the
 * currently-observed delta (`expected = forecast + appliedDelta`) — "what we'd expect the real
 * temperature to be" — drawn only in the future region (right of the fetch dot). This object owns
 * where that label sits, what it reads, and when it shows, so Android and desktop stay consistent.
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
        val tenths = (temp * 10f).roundToInt() // round-half-up at the tenths place
        val sign = if (tenths < 0) "-" else ""
        val mag = abs(tenths)
        return "$sign${mag / 10}.${mag % 10}°"
    }

    /**
     * Resolves the ghost-line label, or null when it should not be drawn (window too wide, no
     * candidates, or no clear hour point on the right half). [plot] is the curve-drawing area
     * (exclude the footer). [drawnBounds] are obstacles already on the canvas. [curveYAt] returns the
     * visible curve's y at a given x, or null off-curve. [padPx] is the edge clearance; [gapPx] is the
     * gap kept between the label and the ghost line it hugs.
     */
    fun place(
        candidates: List<Candidate>,
        spanHours: Long,
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYAt: (Float) -> Float?,
        metrics: Metrics,
        padPx: Float,
        gapPx: Float,
        maxSpanHours: Long = MAX_HOURS_SPAN,
    ): Placement? {
        if (spanHours > maxSpanHours) return null
        if (metrics.width <= 0f || metrics.height <= 0f) return null
        if (candidates.isEmpty()) return null
        if (metrics.width + 2f * padPx > plot.width) return null

        val w = metrics.width
        val h = metrics.height
        val rightCutoff = plot.left + RIGHT_HALF_FRACTION * plot.width
        val eligible = candidates.filter { it.x >= rightCutoff }
        if (eligible.isEmpty()) return null

        // Prefer hours whose footer label is shown so the temp sits over a visible tick; fall back to
        // any right-half hour only if none of the labeled ones have room.
        val labeled = eligible.filter { it.hasHourLabel }
        val tiers = if (labeled.isNotEmpty()) listOf(labeled, eligible) else listOf(eligible)

        for (tier in tiers) {
            var best: Placement? = null
            var bestClearance = -1f
            for (c in tier) {
                val cx = c.x.coerceIn(plot.left + w / 2f + padPx, plot.right - w / 2f - padPx)
                val left = cx - w / 2f
                val right = cx + w / 2f
                // Hug the line: try just above, then just below.
                for (above in booleanArrayOf(true, false)) {
                    val top = if (above) c.ghostY - gapPx - h else c.ghostY + gapPx
                    val box = GraphRect(left, top, right, top + h)
                    if (box.top < plot.top + padPx || box.bottom > plot.bottom - padPx) continue
                    if (drawnBounds.any { it.intersects(box) }) continue
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
            }
            if (best != null) return best // emptiest spot within the most-preferred tier that fits
        }
        return null
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
