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
        /** A low label's °: a slight overlap by area alone is a last resort ([placeNightLabel]). */
        val isDegreeSign: Boolean = false,
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

    /** Where [easeOffNeighbors] left a night label. */
    data class NightLabelEase(val centerX: Float, val top: Float)

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
     * nothing is clear it takes the candidate with the least overlap. Text touching a ° reads as one
     * glyph, so a ° overlap that is slight only by area is used only when no candidate avoids it.
     * [sidewaysCost] weighs sideways movement against down: above 1 prefers down, for when there is
     * lots of room below. Candidates outside [minLeft]..[maxRight] are skipped (home is kept if it is
     * the only one).
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
        sidewaysCost: Float = 1f,
    ): NightLabelPlacement {
        val step = (DailyRainLabels.NIGHT_NUDGE_STEP_DP * density).coerceAtLeast(0.5f)
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val slightArea = width * height * DailyRainLabels.NIGHT_SLIGHT_OVERLAP_AREA_FRACTION
        val maxDownPx = (DailyRainLabels.NIGHT_NUDGE_MAX_DOWN_DP * density)
            .coerceAtMost(maxBottom - (homeTop + height))
            .coerceAtLeast(0f)
        val halfWidth = width / 2f

        // Excess overlap (area beyond "slight"), last-resort ° overlap and the deepest intrusion.
        fun score(cx: Float, top: Float): Triple<Float, Float, Float> {
            var excess = 0f
            var degreeOverlap = 0f
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
                // Side-on contact with a ° is what reads as one glyph; only a graze from below is slight.
                if ((if (o.isDegreeSign) oh else depth) <= slightPx) continue
                if (area > slightArea) excess += area else if (o.isDegreeSign) degreeOverlap += area
            }
            return Triple(excess, degreeOverlap, deepest)
        }

        var best: NightLabelPlacement? = null
        var bestExcess = Float.MAX_VALUE
        var bestDegreeOverlap = Float.MAX_VALUE
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
                    val (excess, degreeOverlap, deepest) = score(cx, homeTop + dy)
                    // Least overlap, then least movement.
                    val cost = kotlin.math.abs(dx) * sidewaysCost + dy
                    val better = when {
                        excess < bestExcess - 0.01f -> true
                        excess > bestExcess + 0.01f -> false
                        degreeOverlap < bestDegreeOverlap - 0.01f -> true
                        degreeOverlap > bestDegreeOverlap + 0.01f -> false
                        else -> cost < bestCost
                    }
                    if (better) {
                        bestExcess = excess
                        bestDegreeOverlap = degreeOverlap
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
            if (bestExcess == 0f && bestDegreeOverlap == 0f && bestCost <= dy) break
        }
        return checkNotNull(best) // home (dx = 0, dy = 0) is always scored
    }

    /**
     * Slides a night label that sank below both lows out from under [leftLowLabel], by just enough to
     * clear it and at most [DailyRainLabels.NIGHT_SINK_RIGHT_DP], stopping short of [maxRight] and of
     * anything level with it. A label that only grazes the low vertically (up to
     * [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP]) stays put, centred in the gap.
     */
    fun slideRightAfterSinking(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        leftLowLabel: Box,
        obstacles: List<Box>,
        maxRight: Float,
        density: Float,
    ): Float {
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        fun isLevel(o: Box) = (minOf(top + height, o.bottom) - maxOf(top, o.top)).let { it > 0f && (o.isDegreeSign || it > slightPx) }
        val needed = leftLowLabel.right - (centerX - width / 2f)
        if (!isLevel(leftLowLabel) || needed <= 0f) return centerX
        val right = centerX + width / 2f
        val nearestLevel = obstacles.filter { isLevel(it) && it.left >= leftLowLabel.right }.minOfOrNull { it.left } ?: maxRight
        val room = minOf(nearestLevel, maxRight) - right
        return centerX + minOf(needed, DailyRainLabels.NIGHT_SINK_RIGHT_DP * density, room).coerceAtLeast(0f)
    }

    /**
     * Moves a placed night label off a neighbour it hugs: within [DailyRainLabels.NIGHT_HUG_DP] of it,
     * or overlapping it, with at least [DailyRainLabels.NIGHT_ROOMY_SIDE_DP] free on the other side.
     *
     * A label the search already moved left ([searchMovedLeft]) has taken the least move that clears,
     * so it does not slide further left: that drifted labels to [maxShiftPx] from the gap's centre.
     *
     * Gaps are measured at the label's own height. A digit box it only grazes vertically (up to
     * [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP]) is not level with it; a ° always is. Hugging the right,
     * it slides left. With no room to, if what it hugs is [rightLowLabel], it drops instead, up to
     * [DailyRainLabels.NIGHT_EASE_MAX_DROP_DP] (above [maxBottom]), to the first row with room on its
     * left, and slides just [DailyRainLabels.NIGHT_EASE_DROP_SLIDE_DP]: a digit box's side padding
     * leaves a clear gap to the ink, and sliding further would put it under the left low instead of in
     * between. Hugging the left, it slides right only when there is something
     * on the right too, evening the gap between them; otherwise a label already clear below a ° stays.
     * A slide evens the two gaps, stops once the near gap is roomy (the far side may be open all the
     * way to the edge), and stays within [maxShiftPx] of [homeCenterX].
     */
    fun easeOffNeighbors(
        homeCenterX: Float,
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        obstacles: List<Box>,
        rightLowLabel: Box?,
        maxShiftPx: Float,
        maxBottom: Float,
        minLeft: Float,
        density: Float,
        searchMovedLeft: Boolean = false,
    ): NightLabelEase {
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val hugPx = DailyRainLabels.NIGHT_HUG_DP * density
        val roomyPx = DailyRainLabels.NIGHT_ROOMY_SIDE_DP * density

        // Free space beside the label with its top at [atTop]; null right = nothing level over there.
        fun gaps(atTop: Float): Pair<Float, Float?> {
            val level = obstacles.filter {
                val overlap = minOf(atTop + height, it.bottom) - maxOf(atTop, it.top)
                overlap > 0f && (it.isDegreeSign || overlap > slightPx)
            }
            val (right, left) = level.partition { (it.left + it.right) / 2f > centerX }
            val leftEdge = maxOf(minLeft, left.maxOfOrNull { it.right } ?: minLeft)
            return (centerX - width / 2f - leftEdge) to right.minOfOrNull { it.left - (centerX + width / 2f) }
        }

        val (gapLeft, gapRight) = gaps(top)
        if (gapRight != null && gapRight <= hugPx) {
            if (gapLeft >= roomyPx) {
                if (searchMovedLeft) return NightLabelEase(centerX, top)
                val shift = minOf((gapLeft - gapRight) / 2f, roomyPx - gapRight)
                return NightLabelEase((centerX - shift).coerceAtLeast(minOf(centerX, homeCenterX - maxShiftPx)), top)
            }
            val hugsRightLow = rightLowLabel != null &&
                minOf(top + height, rightLowLabel.bottom) - maxOf(top, rightLowLabel.top) > slightPx &&
                rightLowLabel.left - (centerX + width / 2f) <= hugPx
            if (!hugsRightLow) return NightLabelEase(centerX, top)
            val step = (DailyRainLabels.NIGHT_NUDGE_STEP_DP * density).coerceAtLeast(0.5f)
            val maxDropPx = (DailyRainLabels.NIGHT_EASE_MAX_DROP_DP * density).coerceAtMost(maxBottom - (top + height))
            for (i in 1..(maxDropPx / step).toInt()) {
                val droppedTop = top + i * step
                if (gaps(droppedTop).first >= roomyPx) {
                    return NightLabelEase(centerX - DailyRainLabels.NIGHT_EASE_DROP_SLIDE_DP * density, droppedTop)
                }
            }
        } else if (gapRight != null && gapLeft <= hugPx && gapRight >= roomyPx) {
            val shift = minOf((gapRight - gapLeft) / 2f, roomyPx - gapLeft)
            return NightLabelEase((centerX + shift).coerceAtMost(maxOf(centerX, homeCenterX + maxShiftPx)), top)
        }
        return NightLabelEase(centerX, top)
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
            Box(digitsRight, top, right, degreeBottom, isDegreeSign = true),
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
