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

    /** Axis-aligned box in canvas pixels (Y grows downward). */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
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

}
