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
        /** A low label's digits: a line box, so its bottom is blank descent ([recenterOverBlankLineBox]). */
        val isLowDigits: Boolean = false,
    )

    /**
     * Where [placeNightLabel] put the night label. [placement] is `home` when nothing was in the
     * way, else which way it moved (`left|right|down|down_left|down_right`); [overlapPx] is the
     * deepest intrusion into any obstacle (0 when clear), for the log line. [inPocket]: it sits under
     * its own day's ° and passed the day-name floor to fit there. [underRightLow]: more than half of
     * it lies under the next day's low digits, where it reads as that day's label.
     */
    data class NightLabelPlacement(
        val centerX: Float,
        val top: Float,
        val placement: String,
        val dxPx: Float,
        val dyPx: Float,
        val overlapPx: Float,
        val inPocket: Boolean = false,
        val underRightLow: Boolean = false,
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
     *
     * The label belongs to the left day. Among candidates equally clear, one that does not lie mostly
     * under [rightDigits] (the next day's low; more than half the label's width) wins before movement
     * is compared: under that number it reads as the next day's label. And a candidate in the pocket
     * under [pocketDegree], the left low's °, may go down to [pocketMaxBottom], past [maxBottom]: the
     * coldest low sits on the day-name row, which put its pocket just below the floor. Those rows are
     * tried only when everything above the floor left the label under the next day's low (Fold and
     * emulator ".3in", 2026-11-14, plans/261114-night-rain-label-own-degree-pocket.md).
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
        pocketDegree: Box? = null,
        pocketMaxBottom: Float = maxBottom,
        rightDigits: Box? = null,
    ): NightLabelPlacement {
        val step = (DailyRainLabels.NIGHT_NUDGE_STEP_DP * density).coerceAtLeast(0.5f)
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val slightArea = width * height * DailyRainLabels.NIGHT_SLIGHT_OVERLAP_AREA_FRACTION
        val maxDownPx = (DailyRainLabels.NIGHT_NUDGE_MAX_DOWN_DP * density)
            .coerceAtMost(maxBottom - (homeTop + height))
            .coerceAtLeast(0f)
        val halfWidth = width / 2f
        // Rows past the floor exist only for the pocket; the last one is the pocket's exact limit.
        val pocketDownPx = if (pocketDegree == null) {
            maxDownPx
        } else {
            (DailyRainLabels.NIGHT_NUDGE_MAX_DOWN_DP * density)
                .coerceAtMost(pocketMaxBottom - (homeTop + height))
                .coerceAtLeast(maxDownPx)
        }
        val rows = (0..(pocketDownPx / step).toInt()).map { it * step }.let {
            if (pocketDownPx > maxDownPx && pocketDownPx - it.last() > 0.01f) it + pocketDownPx else it
        }
        fun inPocket(cx: Float, top: Float) = pocketDegree != null &&
            minOf(cx + halfWidth, pocketDegree.right) > maxOf(cx - halfWidth, pocketDegree.left) &&
            top >= pocketDegree.bottom - slightPx
        fun underRightLow(cx: Float) = rightDigits != null &&
            minOf(cx + halfWidth, rightDigits.right) - maxOf(cx - halfWidth, rightDigits.left) > halfWidth

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
        var bestUnderRight = true
        val xSteps = (maxShiftPx / step).toInt().coerceAtLeast(0)
        for (dy in rows) {
            val pastFloor = dy > maxDownPx + 0.01f
            // The pocket is only for a label that otherwise ends up under the next day's low.
            if (pastFloor && !bestUnderRight) break
            for (xi in 0..xSteps) {
                for (sign in if (xi == 0) intArrayOf(1) else intArrayOf(-1, 1)) {
                    val dx = sign * xi * step
                    val cx = homeCenterX + dx
                    val inBounds = cx - halfWidth >= minLeft && cx + halfWidth <= maxRight
                    if (!inBounds && !(xi == 0 && dy == 0f)) continue
                    if (pastFloor && !inPocket(cx, homeTop + dy)) continue
                    val (excess, degreeOverlap, deepest) = score(cx, homeTop + dy)
                    val underRight = underRightLow(cx)
                    // Least overlap, then its own day's side, then least movement.
                    val cost = kotlin.math.abs(dx) * sidewaysCost + dy
                    val better = when {
                        excess < bestExcess - 0.01f -> true
                        excess > bestExcess + 0.01f -> false
                        degreeOverlap < bestDegreeOverlap - 0.01f -> true
                        degreeOverlap > bestDegreeOverlap + 0.01f -> false
                        underRight != bestUnderRight && best != null -> !underRight
                        else -> cost < bestCost
                    }
                    if (better) {
                        bestExcess = excess
                        bestDegreeOverlap = degreeOverlap
                        bestCost = cost
                        bestUnderRight = underRight
                        best = NightLabelPlacement(
                            inPocket = pastFloor,
                            underRightLow = underRight,
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
            if (bestExcess == 0f && bestDegreeOverlap == 0f && !bestUnderRight && bestCost <= dy) break
        }
        return checkNotNull(best) // home (dx = 0, dy = 0) is always scored
    }

    /**
     * Slides a night label that sank below both lows out from under [leftLowLabel]'s digits, by just enough
     * to clear them and at most [DailyRainLabels.NIGHT_SINK_RIGHT_DP], stopping short of [maxRight] and of
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
        val digits = obstacles.firstOrNull { it.isLowDigits && it.left == leftLowLabel.left } ?: leftLowLabel
        val needed = digits.right - (centerX - width / 2f)
        if (!isLevel(digits) || needed <= 0f) return centerX
        val right = centerX + width / 2f
        val nearestLevel = obstacles.filter { isLevel(it) && it.left >= digits.right && !(it.isDegreeSign && it.left == digits.right) }.minOfOrNull { it.left } ?: maxRight
        val room = minOf(nearestLevel, maxRight) - right
        return centerX + minOf(needed, DailyRainLabels.NIGHT_SINK_RIGHT_DP * density, room).coerceAtLeast(0f)
    }

    /**
     * Slides a placed night label right, off a left neighbour it is pressed against (an icon or a
     * low's digits, level with it, whose centre is left of the label's left edge), until
     * [DailyRainLabels.NIGHT_POCKET_SIDE_GAP_DP] separates them; at most
     * [DailyRainLabels.NIGHT_POCKET_SIDE_SLIDE_MAX_DP]. A ° is not a neighbour here: the ° steps own
     * that contact. Fold Tue "97%" in its ° pocket read "45°97%", and emulator Tue "97%" was accepted
     * 4 px into Tue's cloud icon box by the slight-overlap allowance; [easeOffNeighbors] slides right only
     * with something level on the right, and the pocket placement skips it
     * (plans/261115-night-rain-label-pocket-side-gap.md).
     *
     * It does not slide when the slid label would overlap an obstacle it was clear of, cross
     * [maxRight], or reach under [rightDigits] less than [DailyRainLabels.NIGHT_POCKET_RIGHT_CLEAR_DP]
     * below them (there it would read as the next day's label).
     *
     * [leadingBlankPx] is the width of the label's leading decimal point (0 for other labels). The
     * dot is a speck at [baselineY]; when it lies below the neighbour's ink the label's ink starts
     * that much further right, so it counts as gap. Without it the Fold's Tue ".45in" was slid 1.9 dp
     * off centre to open a gap the dot already gave
     * (plans/261115-night-amount-leading-dot-and-small-trace.md).
     */
    fun slideOffLeftNeighbor(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        rightDigits: Box?,
        obstacles: List<Box>,
        maxRight: Float,
        density: Float,
        leadingBlankPx: Float = 0f,
        baselineY: Float = top + height,
    ): Float {
        val half = width / 2f
        val bottom = top + height
        val left = centerX - half
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val neighbor = obstacles
            .filter { !it.isDegreeSign && (it.left + it.right) / 2f < left }
            .filter { minOf(bottom, it.bottom) - maxOf(top, it.top) > slightPx }
            .maxByOrNull { it.right } ?: return centerX
        val neighborRight = neighbor.right
        val neighborInkBottom = if (neighbor.isLowDigits) {
            neighbor.bottom - (neighbor.bottom - neighbor.top) * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION
        } else {
            neighbor.bottom
        }
        // The dot is about as tall as it is wide.
        val dotBelowInk = leadingBlankPx > 0f && baselineY - leadingBlankPx >= neighborInkBottom
        val gap = left - neighborRight + if (dotBelowInk) leadingBlankPx else 0f
        val wanted = DailyRainLabels.NIGHT_POCKET_SIDE_GAP_DP * density
        if (gap >= wanted) return centerX
        val dx = minOf(wanted - gap, DailyRainLabels.NIGHT_POCKET_SIDE_SLIDE_MAX_DP * density)
        val cx = centerX + dx
        if (cx + half > maxRight) return centerX
        fun overlaps(o: Box, l: Float, r: Float) =
            minOf(r, o.right) > maxOf(l, o.left) && minOf(bottom, o.bottom) > maxOf(top, o.top)
        val newlyHit = obstacles.any { overlaps(it, cx - half, cx + half) && !overlaps(it, left, centerX + half) }
        if (newlyHit) return centerX
        if (rightDigits != null && minOf(cx + half, rightDigits.right) > maxOf(cx - half, rightDigits.left) &&
            top - rightDigits.bottom < DailyRainLabels.NIGHT_POCKET_RIGHT_CLEAR_DP * density
        ) {
            return centerX
        }
        return cx
    }

    /**
     * Moves a placed night label off a neighbour it hugs: within [DailyRainLabels.NIGHT_HUG_DP] of it,
     * or overlapping it, with at least [DailyRainLabels.NIGHT_ROOMY_SIDE_DP] free on the other side.
     *
     * A label the search already moved left ([searchMovedLeft]) has taken the least move that clears,
     * so it does not slide further left: that drifted labels to [maxShiftPx] from the gap's centre.
     *
     * Gaps are measured at the label's own height. A digit box it only grazes vertically (up to
     * [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP]) is not level with it; a ° always is. Whether it hugs the
     * right is measured to the right digits' ink: the bottom [DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION]
     * of that box is blank, and a label under it was slid a full 30 % of a column off centre
     * (emulator Wed "87%", 2026-11-14). Hugging the right,
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
        // [toInk]: the right low's digits start and end at their ink, so a label under their blank
        // descent is below them, and one over their blank ascent above them, not beside. Only for deciding whether it hugs the right at all: the left side
        // and the drop rows keep the line box, which the approved placements were tuned against
        // (emulator "4%" 2026-10-30; "44%" and "Trace" moved 2 dp when this was wider).
        fun gaps(atTop: Float, toInk: Boolean = false): Pair<Float, Float?> {
            val level = obstacles.filter {
                val rightInk = toInk && it.isLowDigits && (it.left + it.right) / 2f > centerX
                val bottom = if (rightInk) it.bottom - (it.bottom - it.top) * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION else it.bottom
                // The top of the box is blank too: Fold tonight "88%" eased 6.2 dp left into Today's
                // column off the empty top of Mon's "46" (plans/261115-tonight-rain-label-ease-blank-ascent.md).
                val inkTop = if (rightInk) it.top + (it.bottom - it.top) * DailyRainLabels.LOW_BLANK_ASCENT_FRACTION else it.top
                val overlap = minOf(atTop + height, bottom) - maxOf(atTop, inkTop)
                overlap > 0f && (it.isDegreeSign || overlap > slightPx)
            }
            val (right, left) = level.partition { (it.left + it.right) / 2f > centerX }
            val leftEdge = maxOf(minLeft, left.maxOfOrNull { it.right } ?: minLeft)
            return (centerX - width / 2f - leftEdge) to right.minOfOrNull { it.left - (centerX + width / 2f) }
        }

        // Whether the nearest level thing on the right is an icon: not a low's digits or °, nor inside [rightLowLabel].
        fun nearestRightIsIcon(atTop: Float): Boolean = obstacles
            .filter { (it.left + it.right) / 2f > centerX }
            .filter {
                val bottom = if (it.isLowDigits) it.bottom - (it.bottom - it.top) * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION else it.bottom
                val inkTop = if (it.isLowDigits) it.top + (it.bottom - it.top) * DailyRainLabels.LOW_BLANK_ASCENT_FRACTION else it.top
                val overlap = minOf(atTop + height, bottom) - maxOf(atTop, inkTop)
                overlap > 0f && (it.isDegreeSign || overlap > slightPx)
            }
            .minByOrNull { it.left }
            ?.let { o ->
                val partOfRightLow = rightLowLabel != null && o.left >= rightLowLabel.left - 0.5f &&
                    o.right <= rightLowLabel.right + 0.5f && o.top >= rightLowLabel.top - 0.5f && o.bottom <= rightLowLabel.bottom + 0.5f
                !o.isLowDigits && !o.isDegreeSign && !partOfRightLow
            } ?: false

        val (gapLeft, gapRight) = gaps(top, toInk = true)
        if (gapRight != null && gapRight <= hugPx) {
            if (gapLeft >= roomyPx) {
                if (searchMovedLeft) return NightLabelEase(centerX, top)
                val wantedRight = if (nearestRightIsIcon(top)) DailyRainLabels.NIGHT_ICON_SIDE_GAP_DP * density else roomyPx
                val shift = minOf((gapLeft - gapRight) / 2f, wantedRight - gapRight)
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
     * Brings a night label the search moved sideways back toward [homeCenterX] when only the blank part
     * of a low label's line box kept it there: the top [DailyRainLabels.LOW_BLANK_ASCENT_FRACTION] of
     * digits and °, and the bottom [DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION] of digits, hold no ink.
     * Tries the column nearest the centre first, at the smallest vertical move: down at most
     * [DailyRainLabels.NIGHT_RECENTER_MAX_DROP_DP], never past [maxBottom], or up at most
     * [DailyRainLabels.NIGHT_RECENTER_MAX_RISE_DP]. It needs real clearance:
     * within [DailyRainLabels.NIGHT_RECENTER_INK_OVERLAP_DP] of the ink. A label level with the digits'
     * ink stays where the search put it.
     */
    fun recenterOverBlankLineBox(
        homeCenterX: Float,
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        obstacles: List<Box>,
        maxBottom: Float,
        density: Float,
    ): NightLabelEase {
        val offset = centerX - homeCenterX
        if (offset == 0f) return NightLabelEase(centerX, top)
        val step = (DailyRainLabels.NIGHT_NUDGE_STEP_DP * density).coerceAtLeast(0.5f)
        val inkOverlapPx = DailyRainLabels.NIGHT_RECENTER_INK_OVERLAP_DP * density
        val maxDropPx = DailyRainLabels.NIGHT_RECENTER_MAX_DROP_DP * density
        val inkBoxes = inkBoxes(obstacles)
        fun clearOfInk(cx: Float, atTop: Float) = inkBoxes.none {
            val ow = minOf(cx + width / 2f, it.right) - maxOf(cx - width / 2f, it.left)
            val oh = minOf(atTop + height, it.bottom) - maxOf(atTop, it.top)
            ow > 0f && oh > inkOverlapPx
        }
        val maxRisePx = DailyRainLabels.NIGHT_RECENTER_MAX_RISE_DP * density
        val rows = ((-(maxRisePx / step).toInt())..(maxDropPx / step).toInt()).sortedWith(
            compareBy({ kotlin.math.abs(it) }, { -it }),
        )
        val columns = (kotlin.math.abs(offset) / step).toInt()
        for (c in 0 until columns.coerceAtLeast(1)) {
            val cx = homeCenterX + (if (offset > 0f) c else -c) * step
            for (i in rows) {
                val atTop = minOf(top + i * step, maxBottom - height)
                if (clearOfInk(cx, atTop)) return NightLabelEase(cx, atTop)
            }
        }
        return NightLabelEase(centerX, top)
    }

    /**
     * Drops a night label off a low label's digit ink, for at most
     * [DailyRainLabels.NIGHT_INK_MAX_DROP_DP] and never past [maxBottom]. The search accepts an overlap
     * up to 15 % of the label's area, which down a tall side edge is text touching text. Only an overlap
     * wider than [DailyRainLabels.NIGHT_INK_SIDE_OVERLAP_DP] counts, and only digits it can clear, to
     * within twice [DailyRainLabels.NIGHT_RECENTER_INK_OVERLAP_DP], as near as [maxBottom] allows.
     */
    fun dropOffDigitInk(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        obstacles: List<Box>,
        maxBottom: Float,
        density: Float,
    ): Float {
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val sidePx = DailyRainLabels.NIGHT_INK_SIDE_OVERLAP_DP * density
        val inkOverlapPx = DailyRainLabels.NIGHT_RECENTER_INK_OVERLAP_DP * density
        val lowest = minOf(top + DailyRainLabels.NIGHT_INK_MAX_DROP_DP * density, maxBottom - height)
        // Digits too tall to clear are left out: dropping only slides the label down their side.
        val target = obstacles
            .filter { it.isLowDigits && minOf(right, it.right) - maxOf(left, it.left) > sidePx }
            .mapNotNull {
                val h = it.bottom - it.top
                val inkTop = it.top + h * DailyRainLabels.LOW_BLANK_ASCENT_FRACTION
                val inkBottom = it.bottom - h * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION
                val level = top + height > inkTop && top < inkBottom - inkOverlapPx
                if (level && inkBottom - lowest <= 2f * inkOverlapPx) inkBottom else null
            }
            .maxOrNull() ?: return top
        return maxOf(top, minOf(target, lowest))
    }

    /**
     * Slides a night label sideways off a neighbour's digit ink when it is level with that ink, leaving
     * [DailyRainLabels.NIGHT_INK_SIDE_GAP_DP] of air, and staying within [minCenterX]..[maxCenterX]. Off
     * the left low it needs more than [DailyRainLabels.NIGHT_INK_SIDE_OVERLAP_DP] of overlap, off the right
     * low more than [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP], and it stops short of the other low's ink. A label below or above the ink stays: that overlap is blank line box.
     */
    fun slideOffDigitInk(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        leftLowLabel: Box,
        rightLowLabel: Box?,
        obstacles: List<Box>,
        minCenterX: Float,
        maxCenterX: Float,
        density: Float,
    ): Float {
        val inkOverlapPx = DailyRainLabels.NIGHT_RECENTER_INK_OVERLAP_DP * density
        val gapPx = DailyRainLabels.NIGHT_INK_SIDE_GAP_DP * density
        fun digitsOf(low: Box?) = low?.let { l -> obstacles.firstOrNull { it.isLowDigits && it.left == l.left } }
        fun levelWithInk(digits: Box): Boolean {
            val h = digits.bottom - digits.top
            val inkTop = digits.top + h * DailyRainLabels.LOW_BLANK_ASCENT_FRACTION
            val inkBottom = digits.bottom - h * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION
            return minOf(top + height, inkBottom) - maxOf(top, inkTop) > inkOverlapPx
        }
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val rightDigits = digitsOf(rightLowLabel)?.takeIf(::levelWithInk)
        val leftDigits = digitsOf(leftLowLabel)?.takeIf(::levelWithInk)
        if (rightDigits != null && right - rightDigits.left > slightPx) {
            val wanted = right - rightDigits.left + gapPx
            val room = leftDigits?.let { left - it.right } ?: Float.MAX_VALUE
            return minOf(centerX, (centerX - minOf(wanted, room)).coerceAtLeast(minCenterX))
        }
        if (leftDigits != null && leftDigits.right - left > DailyRainLabels.NIGHT_INK_SIDE_OVERLAP_DP * density) {
            val wanted = leftDigits.right - left + gapPx
            val room = (rightDigits?.left ?: Float.MAX_VALUE) - right
            return maxOf(centerX, (centerX + minOf(wanted, room)).coerceAtMost(maxCenterX))
        }
        return centerX
    }

    /**
     * Drops a night label sitting just under a ° until it has [DailyRainLabels.NIGHT_DEGREE_GAP_DP] of
     * air, for at most [DailyRainLabels.NIGHT_DEGREE_MAX_DROP_DP] and as far as [maxBottom] allows. The
     * search calls a graze of the ° clear, which leaves the two reading as one glyph. It stays put when
     * the drop would run into anything else; a slight overlap (either axis) is still fine there.
     */
    fun dropClearOfDegree(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        obstacles: List<Box>,
        maxBottom: Float,
        density: Float,
    ): Float {
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val gapPx = DailyRainLabels.NIGHT_DEGREE_GAP_DP * density
        val maxDropPx = DailyRainLabels.NIGHT_DEGREE_MAX_DROP_DP * density
        val target = obstacles
            .filter { it.isDegreeSign && minOf(right, it.right) > maxOf(left, it.left) && top > it.top }
            .maxOfOrNull { it.bottom + gapPx } ?: return top
        if (target - top <= 0f || target - top > maxDropPx) return top
        val landing = minOf(target, maxBottom - height)
        if (landing <= top) return top
        val blocked = obstacles.any {
            !it.isDegreeSign &&
                minOf(right, it.right) - maxOf(left, it.left) > slightPx &&
                minOf(landing + height, it.bottom) - maxOf(landing, it.top) > slightPx
        }
        return if (blocked) top else landing
    }

    /**
     * Slides a night label right off a ° it still grazes because the day-name row left no room to drop
     * clear of it, by at most [DailyRainLabels.NIGHT_SINK_RIGHT_DP], stopping short of [maxCenterX] and
     * of anything else level with it.
     */
    fun slideOffDegree(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        obstacles: List<Box>,
        maxCenterX: Float,
        density: Float,
    ): Float {
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val degree = obstacles.firstOrNull {
            it.isDegreeSign && top > it.top && top < it.bottom &&
                minOf(right, it.right) - maxOf(left, it.left) > 0f && (it.left + it.right) / 2f < centerX
        } ?: return centerX
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val room = obstacles
            .filter {
                it !== degree && it.right > right &&
                    (minOf(top + height, it.bottom) - maxOf(top, it.top)) > slightPx
            }
            .minOfOrNull { it.left - right } ?: Float.MAX_VALUE
        val wanted = minOf(degree.right - left, DailyRainLabels.NIGHT_SINK_RIGHT_DP * density, room)
        return (centerX + wanted.coerceAtLeast(0f)).coerceAtMost(maxOf(centerX, maxCenterX))
    }

    /**
     * Eases a night label the search moved left, onto the day-name floor and under [leftLowLabel]'s
     * digits: back right by up to [DailyRainLabels.NIGHT_FLOOR_SLIDE_DP] (never past [homeCenterX]) and
     * [DailyRainLabels.NIGHT_FLOOR_DROP_DP] past [maxBottom], which only spends the blank descent at the
     * bottom of the label's own line box. No drop step can do this: they all stop at [maxBottom].
     * User, 2026-10-31, Fold NWS "48%" under "47.9°".
     */
    fun easeOffLeftLowAtFloor(
        homeCenterX: Float,
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        leftLowLabel: Box,
        obstacles: List<Box>,
        maxBottom: Float,
        density: Float,
    ): NightLabelEase {
        val atFloor = maxBottom - (top + height) < density
        val digits = obstacles.firstOrNull { it.isLowDigits && it.left == leftLowLabel.left }
        val underDigits = digits != null && centerX - width / 2f < digits.right
        if (centerX >= homeCenterX || !atFloor || !underDigits) return NightLabelEase(centerX, top)
        val slide = minOf(DailyRainLabels.NIGHT_FLOOR_SLIDE_DP * density, homeCenterX - centerX)
        return NightLabelEase(centerX + slide, maxBottom - height + DailyRainLabels.NIGHT_FLOOR_DROP_DP * density)
    }

    /**
     * Drops a night label on the day-name floor that is still side-on against a ° (level with it by
     * more than [DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP]) past that floor, by at most
     * [DailyRainLabels.NIGHT_FLOOR_DEGREE_DROP_DP] and [blankBelowPx], the blank line box under its own
     * digits. [dropClearOfDegree] stops at the floor. User, 2026-10-31, desktop OWM "37%" on "47.6°".
     */
    fun dropOffDegreeAtFloor(
        centerX: Float,
        top: Float,
        width: Float,
        height: Float,
        blankBelowPx: Float,
        obstacles: List<Box>,
        maxBottom: Float,
        density: Float,
    ): Float {
        if (maxBottom - (top + height) >= density) return top
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val slightPx = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * density
        val overlap = obstacles
            .filter { it.isDegreeSign && minOf(right, it.right) > maxOf(left, it.left) }
            .maxOfOrNull { it.bottom - top } ?: return top
        if (overlap <= slightPx) return top
        return top + minOf(overlap, DailyRainLabels.NIGHT_FLOOR_DEGREE_DROP_DP * density, blankBelowPx).coerceAtLeast(0f)
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
            return listOf(Box(left, top, right, bottom, isLowDigits = true))
        }
        val digitsRight = right - degreeWidth
        val degreeBottom = top + (bottom - top) * DailyRainLabels.DEGREE_SIGN_HEIGHT_FRACTION
        return listOf(
            Box(left, top, digitsRight, bottom, isLowDigits = true),
            Box(digitsRight, top, right, degreeBottom, isDegreeSign = true),
        )
    }

    /**
     * [obstacles] cut to their ink: a low label's line box has no ink in its top
     * [DailyRainLabels.LOW_BLANK_ASCENT_FRACTION], and its digits none in the bottom
     * [DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION]. Icons are kept whole.
     */
    fun inkBoxes(obstacles: List<Box>): List<Box> = obstacles.map {
        val h = it.bottom - it.top
        when {
            it.isLowDigits -> it.copy(
                top = it.top + h * DailyRainLabels.LOW_BLANK_ASCENT_FRACTION,
                bottom = it.bottom - h * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION,
            )
            // The ° box is already cut to the top half of the line box, so its fraction doubles.
            it.isDegreeSign -> it.copy(
                top = it.top + h * DailyRainLabels.LOW_BLANK_ASCENT_FRACTION / DailyRainLabels.DEGREE_SIGN_HEIGHT_FRACTION,
            )
            else -> it
        }
    }

    private fun nudgeName(dx: Float, dy: Float): String = when {
        dy == 0f && dx == 0f -> "home"
        dy == 0f -> if (dx < 0f) "left" else "right"
        dx == 0f -> "down"
        dx < 0f -> "down_left"
        else -> "down_right"
    }
}
