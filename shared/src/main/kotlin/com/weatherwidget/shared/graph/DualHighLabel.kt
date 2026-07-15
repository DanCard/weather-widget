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
 * scales with how compressed each device's graph is. There is no meaningful horizontal escape: a
 * temp label is often wider than its own column at 10 days, so the two boxes are near-coincident in
 * X and this vertical test is close to the real overlap.
 *
 * That gap is small — it IS the forecast miss — so at their natural positions the two labels
 * collide on any modest miss. [bottomOffsetsDp] therefore repositions both before they are drawn,
 * and the room test above must be fed those adjusted positions.
 *
 * Pure functions so both the Android ([com.weatherwidget] widget renderer) and the desktop Compose
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

    /**
     * Extra shrink for the FORECAST high label in the dual case (stacks with [TWO_LABEL_FONT_SCALE]).
     *
     * The actual is the headline number and should read larger. Without this the opposite often
     * happened by accident: a decimal actual like `89.4°` trips [isWideLabel] and shrinks 5%, while
     * an integer forecast like `87°` does not — so the forecast drew *bigger* purely on digit count.
     * Keyed to the forecast ROLE, not to whichever label happens to sit lower.
     *
     * Doubles as the main declutter lever, because the forecast label's bottom is pinned near its
     * bar top and the text grows upward: shrinking it moves its top DOWN, away from the actual.
     */
    const val DUAL_FORECAST_FONT_SCALE = 0.82f

    /**
     * Where the LOWER-valued of the two high labels sits: its bottom edge this far below its own
     * bar's top, instead of the usual gap above it. Negative = just ABOVE the bar top. Giving up the
     * whole normal gap is what buys the room; the exact sign here is fine tuning.
     *
     * Note this label cannot be lifted clear of "the bars" by tuning: it draws at its own bar's X,
     * but the *taller* bars beside it (the actual, and today's snapshot) pass through it no matter
     * where it sits, short of climbing to the actual's height where the actual's label already is.
     * That is what the outline is for. [DUAL_FORECAST_FONT_SCALE] is the lever that declutters here.
     *
     * Stated as an absolute target rather than a "push down by N" delta because the two renderers
     * use different normal gaps (Android [8dp][HIGH label offset], desktop 3) — a shared delta would
     * land them in different places, an absolute target lands them the same.
     */
    const val DUAL_LOWER_BELOW_BAR_DP = -5f

    /**
     * How much further UP (dp) the higher-valued of the two high labels goes, beyond its normal gap.
     * Deliberately much smaller than the lower label's move: up is the expensive direction (the rain
     * % and the day header live there) and past attempts at a bigger raise read as exaggerated.
     */
    const val DUAL_UPPER_PUSH_UP_DP = 2f

    /** True when a formatted temp has 3+ numeric digits (triple-digit ints or decimals like 84.3). */
    fun isWideLabel(label: String): Boolean = label.count { it.isDigit() } >= 3

    /**
     * Vertical placement of the two dual high labels, as each label's BOTTOM edge offset from its
     * own bar's top (positive = below the bar top, i.e. down the screen).
     *
     * Each label moves *away* from the other — the lower-valued one down onto its bar, the
     * higher-valued one a little further up — rather than "the forecast always moves down". The
     * forecast is not always the lower of the two (a forecast that ran hot sits above the actual),
     * and pushing by role would then shove the two labels together and could even cross them,
     * printing the forecast number below the actual's while the forecast bar sits above it. Moving
     * apart can never reorder them.
     *
     * @param normalGapDp the platform's usual gap between a high label's bottom and its bar top
     */
    fun bottomOffsetsDp(actualHigh: Float, forecastHigh: Float, normalGapDp: Float): Offsets {
        val lower = DUAL_LOWER_BELOW_BAR_DP
        val upper = -(normalGapDp + DUAL_UPPER_PUSH_UP_DP)
        return if (actualHigh >= forecastHigh) {
            Offsets(actualDp = upper, forecastDp = lower)
        } else {
            Offsets(actualDp = lower, forecastDp = upper)
        }
    }

    /** Each dual label's bottom edge relative to its own bar top, in dp; positive = below. */
    data class Offsets(val actualDp: Float, val forecastDp: Float)

    /**
     * @param actualLabelTopY top Y of the actual-high label box, with [bottomOffsetsDp] already
     *   applied — the room test must measure the boxes as they will actually be drawn
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
