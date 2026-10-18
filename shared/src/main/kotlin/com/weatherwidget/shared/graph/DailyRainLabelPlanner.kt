package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.DailyRainLabels

/**
 * Pure geometry and placement planner for the daily-forecast graph's rain labels.
 *
 * Consumed by both Android (:app) and Desktop (:desktop) to ensure identical positioning,
 * interstitial tucking, and collision handling.
 */
object DailyRainLabelPlanner {

    data class TextMetrics(
        val ascent: Float,
        val descent: Float,
    )

    data class RainAboveAnchorPlacement(
        val baseline: Float,
        val top: Float,
        val bottom: Float,
        val anchorTop: Float,
        val fits: Boolean,
    )

    data class NightTuckCalculations(
        val tightFraction: Float,
        val dynamicOverlapDp: Float,
        val roomFraction: Float,
        val roomyDownDp: Float,
    )

    /** Axis-aligned box in canvas pixels (Y grows downward). */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /**
     * Where [placeNightLabel] put the night label. [placement] is `home` when nothing was in the
     * way, else which way it moved (`left|right|down|down_left|down_right`); [overlapPx] is the
     * deepest intrusion into any obstacle (0 when clear), for the log line.
     */
    data class NightLabelPlacement(
        val centerX: Float,
        val top: Float,
        val placement: String,
        val dxPx: Float,
        val dyPx: Float,
        val overlapPx: Float,
    )

    /**
     * Resolves the anchor top Y in screen coordinates (where Y=0 is canvas top, growing downward).
     *
     * In Today's column or dual-high columns, if a prior-day forecast snapshot bar or secondary
     * high extends higher than the headline high label (meaning its screen Y is smaller), this
     * returns the topmost boundary (`minOf`) so the daytime rain label clears both and never
     * collides with either.
     */
    fun resolveDayRainAnchorTop(
        highLabelTop: Float?,
        snapshotBarTop: Float?,
        fallbackTop: Float? = null,
    ): Float? {
        val candidates = listOfNotNull(highLabelTop, snapshotBarTop)
        return candidates.minOrNull() ?: fallbackTop
    }

    /**
     * Resolves daytime rain label placement above an anchor top Y.
     * Note: [gap] is typically negative (e.g. -3dp), meaning a slight overlap/tuck into the top of
     * the anchor. The label's bottom sits at `anchorTop - gap`.
     */
    fun resolveRainAboveAnchorPlacement(
        anchorTop: Float,
        ascent: Float,
        descent: Float,
        topMargin: Float,
        gap: Float,
    ): RainAboveAnchorPlacement {
        val baseline = anchorTop - gap - descent
        val top = baseline + ascent
        val bottom = baseline + descent
        return RainAboveAnchorPlacement(
            baseline = baseline,
            top = top,
            bottom = bottom,
            anchorTop = anchorTop,
            fits = top >= topMargin,
        )
    }

    /**
     * Convenience helper for callers measuring fonts via ascent/descent metrics.
     * When [snapshotBarTop] is supplied (e.g. for Today), clears the snapshot bar if it reaches
     * higher than the high-temp label.
     */
    fun resolveRainAboveHighPlacement(
        highBaseline: Float,
        highMetrics: TextMetrics,
        rainMetrics: TextMetrics,
        topMargin: Float,
        gap: Float,
        snapshotBarTop: Float? = null,
    ): RainAboveAnchorPlacement {
        val highLabelTop = highBaseline + highMetrics.ascent
        val anchorTop = resolveDayRainAnchorTop(highLabelTop, snapshotBarTop) ?: highLabelTop
        return resolveRainAboveAnchorPlacement(
            anchorTop = anchorTop,
            ascent = rainMetrics.ascent,
            descent = rainMetrics.descent,
            topMargin = topMargin,
            gap = gap,
        )
    }

    /**
     * The peak-hour label sits on the day rain label as that label sits on the high: bottom at
     * `rainTop - gap`. [ceiling] is the hard top; a label that would cross it is not drawn
     * (`fits = false`) — the % never moves down to make room.
     */
    fun resolvePeakAboveRainPlacement(
        rainTop: Float,
        peakMetrics: TextMetrics,
        ceiling: Float,
        gap: Float,
    ): RainAboveAnchorPlacement = resolveRainAboveAnchorPlacement(
        anchorTop = rainTop,
        ascent = peakMetrics.ascent,
        descent = peakMetrics.descent,
        topMargin = ceiling,
        gap = gap,
    )

    /**
     * Resolves daytime rain label top coordinate for bounding-box layout systems (e.g. Compose Desktop).
     */
    fun resolveRainAboveAnchorTop(
        anchorTop: Float,
        rainHeight: Float,
        gapPx: Float,
        floorY: Float? = null,
    ): Float {
        val unconstrained = anchorTop - gapPx - rainHeight
        return if (floorY != null) unconstrained.coerceAtLeast(floorY) else unconstrained
    }

    /**
     * Pure calculations for interstitial night rain label tucking given room below the low-temp anchor.
     */
    fun calculateNightTuck(roomBelowDp: Float): NightTuckCalculations {
        val tightFraction = (1f - (roomBelowDp - DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP) /
            (DailyRainLabels.NIGHT_TUCK_ROOM_MAX_DP - DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP)).coerceIn(0f, 1f)
        val roomFraction = 1f - tightFraction
        return NightTuckCalculations(
            tightFraction = tightFraction,
            dynamicOverlapDp = DailyRainLabels.NIGHT_TUCK_OVERLAP_BASE_DP * tightFraction,
            roomFraction = roomFraction,
            roomyDownDp = DailyRainLabels.NIGHT_TUCK_ROOMY_DOWN_DP * roomFraction,
        )
    }

    /**
     * Places the night rain label between two columns by collision detection.
     *
     * Starts at [homeCenterX] (the gap midpoint) and [homeTop] (the tuck under the low temps). If an
     * [obstacles] box is in the way it searches, in [DailyRainLabels.NIGHT_NUDGE_STEP_DP] steps,
     * sideways up to [maxShiftPx] either way and down until the label would cross [maxBottom], and
     * keeps the candidate that moves least. Never moves up. A slight overlap — an intrusion no
     * deeper than [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP] on either axis, or covering at most
     * [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_AREA_FRACTION] of the label — counts as clear. When
     * nothing is clear it takes the candidate with the least overlap. Candidates outside
     * [minLeft]..[maxRight] are skipped (home is kept if it is the only one).
     */
    fun placeNightLabel(
        homeCenterX: Float,
        homeTop: Float,
        width: Float,
        height: Float,
        obstacles: List<Box>,
        maxShiftPx: Float,
        maxBottom: Float,
        minLeft: Float,
        maxRight: Float,
        density: Float,
    ): NightLabelPlacement {
        val step = (DailyRainLabels.NIGHT_NUDGE_STEP_DP * density).coerceAtLeast(0.5f)
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val slightArea = width * height * DailyRainLabels.NIGHT_SLIGHT_OVERLAP_AREA_FRACTION
        val maxDownPx = (DailyRainLabels.NIGHT_NUDGE_MAX_DOWN_DP * density)
            .coerceAtMost(maxBottom - (homeTop + height))
            .coerceAtLeast(0f)
        val halfWidth = width / 2f

        // Excess overlap (area beyond "slight") and the deepest intrusion, for one candidate.
        fun score(cx: Float, top: Float): Pair<Float, Float> {
            var excess = 0f
            var deepest = 0f
            val l = cx - halfWidth
            val r = cx + halfWidth
            val b = top + height
            for (o in obstacles) {
                val ow = minOf(r, o.right) - maxOf(l, o.left)
                val oh = minOf(b, o.bottom) - maxOf(top, o.top)
                if (ow <= 0f || oh <= 0f) continue
                val depth = minOf(ow, oh)
                deepest = maxOf(deepest, depth)
                val area = ow * oh
                if (depth > slightPx && area > slightArea) excess += area
            }
            return excess to deepest
        }

        var best: NightLabelPlacement? = null
        var bestExcess = Float.MAX_VALUE
        var bestCost = Float.MAX_VALUE
        val xSteps = (maxShiftPx / step).toInt().coerceAtLeast(0)
        val ySteps = (maxDownPx / step).toInt()
        for (yi in 0..ySteps) {
            val dy = yi * step
            for (xi in 0..xSteps) {
                for (sign in if (xi == 0) intArrayOf(1) else intArrayOf(-1, 1)) {
                    val dx = sign * xi * step
                    val cx = homeCenterX + dx
                    val inBounds = cx - halfWidth >= minLeft && cx + halfWidth <= maxRight
                    if (!inBounds && !(xi == 0 && yi == 0)) continue
                    val (excess, deepest) = score(cx, homeTop + dy)
                    // Least movement wins; sideways and down weigh the same.
                    val cost = kotlin.math.abs(dx) + dy
                    val better = when {
                        excess < bestExcess - 0.01f -> true
                        excess > bestExcess + 0.01f -> false
                        else -> cost < bestCost
                    }
                    if (better) {
                        bestExcess = excess
                        bestCost = cost
                        best = NightLabelPlacement(
                            centerX = cx,
                            top = homeTop + dy,
                            placement = nudgeName(dx, dy),
                            dxPx = dx,
                            dyPx = dy,
                            overlapPx = deepest,
                        )
                    }
                }
            }
            // Every lower row costs more than a clear candidate already found.
            if (bestExcess == 0f && bestCost <= dy) break
        }
        return checkNotNull(best) // home (dx = 0, dy = 0) is always scored
    }

    /**
     * A low-temp label as night-label obstacles: the digits at full height, and — when [text] ends in
     * ° — the degree sign over only the top [DailyRainLabels.DEGREE_SIGN_HEIGHT_FRACTION] of the box.
     * One full-height box made the blank pocket under the ° an obstacle, pushing night labels toward
     * the next day's number (2026-10-17, Silurian "20%" on the Fold).
     */
    fun lowLabelObstacles(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        degreeWidth: Float,
    ): List<Box> {
        if (!text.endsWith("°") || degreeWidth <= 0f || degreeWidth >= right - left) {
            return listOf(Box(left, top, right, bottom))
        }
        val digitsRight = right - degreeWidth
        val degreeBottom = top + (bottom - top) * DailyRainLabels.DEGREE_SIGN_HEIGHT_FRACTION
        return listOf(
            Box(left, top, digitsRight, bottom),
            Box(digitsRight, top, right, degreeBottom),
        )
    }

    private fun nudgeName(dx: Float, dy: Float): String = when {
        dy == 0f && dx == 0f -> "home"
        dy == 0f -> if (dx < 0f) "left" else "right"
        dx == 0f -> "down"
        dx < 0f -> "down_left"
        else -> "down_right"
    }
}
