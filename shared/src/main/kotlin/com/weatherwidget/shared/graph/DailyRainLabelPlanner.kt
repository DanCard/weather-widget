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
        val dynamicNudgeDp: Float,
        val effectiveNudgeDp: Float,
        val roomFraction: Float,
        val roomyRightDp: Float,
        val roomyDownDp: Float,
    )

    data class NightCollisionResult(
        val centerX: Float,
        val baseline: Float,
        val resolution: String, // "none" | "down"
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
    fun calculateNightTuck(
        roomBelowDp: Float,
        isLeftTempLower: Boolean,
    ): NightTuckCalculations {
        val tightFraction = (1f - (roomBelowDp - DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP) /
            (DailyRainLabels.NIGHT_TUCK_ROOM_MAX_DP - DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP)).coerceIn(0f, 1f)
        val dynamicOverlapDp = DailyRainLabels.NIGHT_TUCK_OVERLAP_BASE_DP * tightFraction
        val dynamicNudgeDp = DailyRainLabels.NIGHT_TUCK_NUDGE_BASE_DP +
            (DailyRainLabels.NIGHT_TUCK_NUDGE_RANGE_DP * tightFraction)
        val effectiveNudgeDp = if (isLeftTempLower) dynamicNudgeDp * 0.0f else dynamicNudgeDp

        val roomFraction = 1f - tightFraction
        val roomyRightDp = DailyRainLabels.NIGHT_TUCK_ROOMY_RIGHT_DP * roomFraction
        val roomyDownDp = DailyRainLabels.NIGHT_TUCK_ROOMY_DOWN_DP * roomFraction

        return NightTuckCalculations(
            tightFraction = tightFraction,
            dynamicOverlapDp = dynamicOverlapDp,
            dynamicNudgeDp = dynamicNudgeDp,
            effectiveNudgeDp = effectiveNudgeDp,
            roomFraction = roomFraction,
            roomyRightDp = roomyRightDp,
            roomyDownDp = roomyDownDp,
        )
    }

    /**
     * Calculates the shifted horizontal center for the night rain label.
     */
    fun calculateNightShiftedCenterX(
        columnCenterX: Float,
        columnWidth: Float,
        effectiveNudgePx: Float,
        roomyRightPx: Float,
        scalePx: Float,
    ): Float = columnCenterX + (columnWidth / 2f) - effectiveNudgePx + roomyRightPx + scalePx

    /**
     * Resolves a collision between the night rain label and this day's own low-temp label.
     * When the two overlap, nudges the rain label straight DOWN so its baseline matches the low
     * label's — it then sits beside the number ("56° 12%") and clears the degree symbol above.
     */
    fun resolveNightCollision(
        nightCenterX: Float,
        nightBaseline: Float,
        nightHalfWidth: Float,
        ascent: Float,
        descent: Float,
        ownLeft: Float,
        ownTop: Float,
        ownRight: Float,
        ownBottom: Float,
        ownBaseline: Float,
    ): NightCollisionResult {
        val nightLeft = nightCenterX - nightHalfWidth
        val nightRight = nightCenterX + nightHalfWidth
        val nightTop = nightBaseline + ascent
        val nightBottom = nightBaseline + descent
        val intersects = nightLeft < ownRight && ownLeft < nightRight && nightTop < ownBottom && ownTop < nightBottom
        if (!intersects || ownBaseline <= nightBaseline) {
            return NightCollisionResult(nightCenterX, nightBaseline, "none")
        }
        return NightCollisionResult(nightCenterX, ownBaseline, "down")
    }

    /**
     * Bounding-box collision resolution for Desktop/Compose.
     * If the night rain label box intersects the low label box, and the low label top is lower (greater Y)
     * than the rain label top, snaps the rain label top down to the low label top so they align beside each other.
     */
    fun resolveNightCollisionTop(
        nightLeft: Float,
        nightTop: Float,
        nightWidth: Float,
        nightHeight: Float,
        ownLeft: Float,
        ownTop: Float,
        ownRight: Float,
        ownBottom: Float,
    ): Float {
        val nightRight = nightLeft + nightWidth
        val nightBottom = nightTop + nightHeight
        val intersects = nightLeft < ownRight && ownLeft < nightRight && nightTop < ownBottom && ownTop < nightBottom
        return if (intersects && ownTop > nightTop) ownTop else nightTop
    }
}
