package com.weatherwidget.shared.graph

import kotlin.math.abs

/**
 * Decides whether a past day in the daily view should show BOTH the actual high label
 * (thermostat color) and the forecast high label (forecast-bar color), instead of a single
 * high label.
 *
 * The trigger is "room-based" with a meaningfulness floor:
 *  - the two highs must differ by at least [MIN_DIFF_DEG] (so we never stack two near-identical
 *    numbers), AND
 *  - the two label boxes must not overlap by more than [MAX_OVERLAP_FRACTION] of a label height.
 *
 * Because temperature maps linearly to Y on the daily graph, the vertical gap between the two
 * labels is `|actualHigh - forecastHigh| * pixelsPerDegree`, so this room test automatically
 * scales with how compressed each device's graph is. The labels are also drawn at slightly
 * different X (each above its own bar), so the real overlap is less than this vertical-only test
 * assumes — a deliberately conservative check, in line with the user's "slight overlap is ok".
 *
 * Pure function so both the Android ([com.weatherwidget] widget renderer) and the desktop Compose
 * renderer share one decision; see the codebase's shared-pure-function pattern (LocationMatch,
 * DailyRainLabels).
 */
object DualHighLabel {
    /**
     * Minimum actual-vs-forecast high difference (°) before a second label is ever shown.
     * Deliberately low so the room test below is the real gate — a meaningless ~1° difference
     * never shows two labels, but anything genuinely off does (when there's room).
     */
    const val MIN_DIFF_DEG = 2f

    /** Fraction of a label height the two boxes may overlap and still count as "enough room". */
    const val MAX_OVERLAP_FRACTION = 0.6f

    /** When two high labels are shown for a past day, shrink their font to this fraction (2%). */
    const val TWO_LABEL_FONT_SCALE = 0.98f

    /** Extra shrink (5%) for "wide" labels — temps with 3+ digits, e.g. 100° or 97.7°. */
    const val WIDE_LABEL_FONT_SCALE = 0.95f

    /** True when a formatted temp has 3+ numeric digits (triple-digit ints or decimals like 84.3). */
    fun isWideLabel(label: String): Boolean = label.count { it.isDigit() } >= 3

    /**
     * @param actualLabelTopY top Y of the actual-high label box (already includes the above-bar offset)
     * @param forecastLabelTopY top Y of the forecast-high label box (same convention)
     * @param labelHeightPx height of a high label at the size it will be drawn
     */
    fun showBoth(
        actualHigh: Float?,
        forecastHigh: Float?,
        actualLabelTopY: Float,
        forecastLabelTopY: Float,
        labelHeightPx: Float,
    ): Boolean {
        if (actualHigh == null || forecastHigh == null) return false
        if (abs(actualHigh - forecastHigh) < MIN_DIFF_DEG) return false
        val gap = abs(actualLabelTopY - forecastLabelTopY)
        return gap >= labelHeightPx * (1f - MAX_OVERLAP_FRACTION)
    }
}
