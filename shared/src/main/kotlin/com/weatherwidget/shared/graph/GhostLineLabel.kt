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
 *   Only hours at/right of the **ghost-line start** (the fetch dot) are considered — the ghost line
 *   only exists there — so panning into the future keeps labeling the near-future instead of a fixed
 *   plot midpoint. Hours whose footer label is shown are preferred so the temperature sits above a
 *   visible hour tick.
 * - **Visibility**: up-to-a-day views only, gated by [LABEL_MAX_SPAN_HOURS] (48 h — the point where
 *   the footer stops showing clock-hour ticks, which these labels read against). This covers the
 *   default WIDE view (24 h) and a day-click; it excludes the multi-day THREE_DAY view (72 h). The
 *   caller additionally gates on the ghost line being drawn at all — the same [GhostLineGate] the
 *   line uses (now-indicator visible, meaningful delta, fetch dot present).
 * - **Placement**: hugs the line (small gap above, else below) and is drawn **only when clear** of
 *   every already-placed label and of the curve; returns null when the right half is crowded.
 *
 * Styling (faint, ghost-like) is applied by each platform's paint; this stays free of
 * android.graphics / Compose types and only computes geometry + text.
 */
object GhostLineLabel {
    /** Narrow-only gate; matches the fetch-dot age label ([FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN]). */
    const val MAX_HOURS_SPAN = FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN

    /**
     * Widest visible window (hours) that still gets ghost-line labels. The labels are anchored to the
     * footer's clock-hour ticks ("at 6 PM → 69.4°"), so they only read sensibly while the footer shows
     * clock hours rather than per-day date labels — which both platforms switch to around 48 h (desktop
     * `DesktopGraphUtils.DATE_LABEL_SPAN_THRESHOLD_HOURS`; Android's THREE_DAY date footer). This covers
     * the **default WIDE view (24 h)** and a day-click (24 h) but excludes THREE_DAY (72 h), where a
     * compressed ghost line hour-anchored against dates would just be clutter.
     *
     * Deliberately larger than [MAX_HOURS_SPAN]: the ghost *line* is drawn on any NOW-visible view via
     * [GhostLineGate], so capping the *labels* at 12 h left the default WIDE view showing an unlabeled
     * line. [MAX_HOURS_SPAN] still governs the fetch-dot age label and GhostLineGate's off-left
     * (NOW-off-screen) future-scroll extension; only the label span cap lives here.
     */
    const val LABEL_MAX_SPAN_HOURS = 48L

    /** Curve clearance sampling across the candidate box width. */
    private const val CURVE_SAMPLES = 5

    /**
     * A ghost label may graze the ghost line by up to this fraction of its own height before the spot
     * is rejected. A faint dashed line clipping a label corner is acceptable, and it lets a label sit
     * on a steep segment (e.g. the hour right after NOW) that the strict no-overlap rule skipped.
     * Kept < 0.5 so a line passing through the label's CENTER is still rejected (guard stays meaningful).
     */
    private const val OVERLAP_TOL_FRACTION = 0.4f

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
     * [plot] is the curve-drawing area (exclude the footer). [ghostLineStartX] is the x where the
     * ghost line begins (the fetch dot); only hours at/right of it are eligible, so the labels track
     * the fetch dot as the view pans rather than a fixed plot midpoint. [drawnBounds] are obstacles
     * already on the canvas. [curveYAt] returns the visible curve's y at a given x, or null off-curve.
     * [padPx] is the edge clearance; [gapPx] is the gap kept between a label and the ghost line it hugs.
     *
     * Hours whose footer label is shown are preferred so each temp sits over a visible tick; if no
     * future hour carries a footer label we fall back to all future hours. Labels are placed against
     * a **running obstacle list** (seeded from [drawnBounds]), so each placed ghost label becomes an
     * obstacle for the next — they never stack on each other. Crowding near the fetch dot is handled
     * by the obstacle list (the fetch-dot / current-temp labels sit in [drawnBounds]), not by a cutoff.
     */
    fun placeAll(
        candidates: List<Candidate>,
        spanHours: Long,
        plot: GraphRect,
        ghostLineStartX: Float,
        drawnBounds: List<GraphRect>,
        curveYAt: (Float) -> Float?,
        metrics: Metrics,
        padPx: Float,
        gapPx: Float,
        maxSpanHours: Long = LABEL_MAX_SPAN_HOURS,
    ): List<Placement> {
        if (spanHours > maxSpanHours) return emptyList()
        if (metrics.width <= 0f || metrics.height <= 0f) return emptyList()
        if (candidates.isEmpty()) return emptyList()
        if (metrics.width + 2f * padPx > plot.width) return emptyList()

        // The ghost line only exists right of the fetch dot; anchor eligibility there so panning into
        // the future keeps labeling the near-future (a fixed plot-midpoint cutoff dropped those hours).
        val future = candidates.filter { it.x >= ghostLineStartX }
        if (future.isEmpty()) return emptyList()

        // Prefer hours whose footer label is shown so each temp sits over a visible tick; fall back to
        // any future hour only if none of them carry a footer label.
        val labeled = future.filter { it.hasHourLabel }
        val eligible = (if (labeled.isNotEmpty()) labeled else future).sortedBy { it.x }

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
     * too deeply through it. Of the valid above/below options the one with more curve clearance wins
     * (a clean gap always beats a graze), so a slight overlap is only used when nothing better fits.
     * Null when neither side fits.
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
        val overlapTolPx = h * OVERLAP_TOL_FRACTION
        val cx = c.x.coerceIn(plot.left + w / 2f + padPx, plot.right - w / 2f - padPx)
        val left = cx - w / 2f
        val right = cx + w / 2f
        var best: Placement? = null
        // Signed clearance: + = clean gap, - = graze depth (allowed down to -overlapTolPx). Start below
        // the worst allowed graze so any admissible spot wins; higher clearance (cleaner) still wins.
        var bestClearance = -Float.MAX_VALUE
        // Hug the line: try just above, then just below.
        for (above in booleanArrayOf(true, false)) {
            val top = if (above) c.ghostY - gapPx - h else c.ghostY + gapPx
            val box = GraphRect(left, top, right, top + h)
            if (box.top < plot.top + padPx || box.bottom > plot.bottom - padPx) continue
            if (obstacles.any { it.intersects(box) }) continue
            val clearance = curveClearance(box, curveYAt, overlapTolPx) ?: continue // null = curve intrudes past tol
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
     * Signed vertical clearance between [box] and the visible curve across the box's x-range:
     * positive = the smallest clean gap to the nearest edge (box sits entirely off the curve),
     * negative = the deepest the curve penetrates into the box. Returns null only when that penetration
     * exceeds [overlapTolPx] — i.e. the curve cuts too deep to graze. Letting a box report its gap (or
     * shallow penetration) lets the emptiest / least-overlapping spot win.
     */
    private fun curveClearance(box: GraphRect, curveYAt: (Float) -> Float?, overlapTolPx: Float): Float? {
        var minSigned = Float.MAX_VALUE
        for (i in 0..CURVE_SAMPLES) {
            val x = box.left + (box.right - box.left) * (i.toFloat() / CURVE_SAMPLES)
            val y = curveYAt(x) ?: continue
            // + when the curve is outside the box (distance to nearest edge); - when inside (penetration).
            val signed = when {
                y < box.top -> box.top - y
                y > box.bottom -> y - box.bottom
                else -> -minOf(y - box.top, box.bottom - y)
            }
            if (signed < minSigned) minSigned = signed
        }
        if (minSigned == Float.MAX_VALUE) return Float.MAX_VALUE // curve not sampled in range
        return if (minSigned < -overlapTolPx) null else minSigned
    }
}
