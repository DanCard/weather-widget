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
     * Deliberately low so the room test below is the real gate — the floor only exists to refuse
     * two numbers that would print as effectively the same value.
     *
     * Was 2f, which silently WAS the gate on a normally-sized graph and hid genuine misses: at
     * ~20px/° (Samsung fold, density 3.03) a 1.1° miss clears the room test with ~50% margin, so
     * days like "actual 75.9° / forecast 77.0°" dropped their forecast label with obvious empty
     * space above it. 0.5f is the smallest gap that still shows as two distinct numbers at the
     * one-decimal display precision; below that the room test refuses them anyway (a sub-0.5°
     * miss is only ~10px of natural separation).
     */
    const val MIN_DIFF_DEG = 0.5f

    /** Fraction of a label height the two boxes may overlap and still count as "enough room". */
    const val MAX_OVERLAP_FRACTION = 0.6f

    /** When two high labels are shown for a past day, shrink their font to this fraction (2%). */
    const val TWO_LABEL_FONT_SCALE = 0.98f

    /** Extra shrink (10%) for "wide" labels — temps with 3+ digits, e.g. 100° or 97.7°. */
    const val WIDE_LABEL_FONT_SCALE = 0.9f

    /**
     * Extra shrink for the FORECAST high label (stacks with [TWO_LABEL_FONT_SCALE]) — applied ONLY
     * when the two labels would collide at full size; see [forecastFontScale]. A well-separated
     * forecast keeps the normal font. Keyed to the forecast ROLE (the secondary number), not to
     * whichever label happens to sit lower.
     *
     * It works as a collision remedy because the forecast label's bottom is pinned near its bar top
     * and the text grows upward: shrinking it moves its top DOWN, away from the actual.
     */
    const val DUAL_FORECAST_FONT_SCALE = 0.82f

    /**
     * Box overlap (fraction of a label height) the full-size labels may have before the forecast
     * shrinks. A measured label box is taller than its visible digits (line-height padding), so
     * boxes that just touch still LOOK clearly separated — shrinking there reads as arbitrary
     * (the "yesterday on desktop" report). Matches the codebase's 0.30 minor-overlap convention
     * (hard-bound value labels use the same tolerance).
     */
    const val FONT_SHRINK_ALLOWED_OVERLAP_FRACTION = 0.30f

    /**
     * Font scale for the FORECAST high label in the dual case: [DUAL_FORECAST_FONT_SCALE] when the
     * two labels genuinely collide drawn at full size, 1f otherwise. The shrink is a collision
     * remedy, not a permanent style — the forecast only gives up size when the actual needs the room,
     * and a small box overlap ([FONT_SHRINK_ALLOWED_OVERLAP_FRACTION]) doesn't count as a collision.
     *
     * Pass both labels' Y at the SAME edge (both tops or both bottoms — digits have no descenders,
     * so bottom edge == baseline), with the forecast measured at FULL (unshrunk) size, plus the
     * full-size label height. Y grows downward (both renderers).
     */
    fun forecastFontScale(actualEdgeY: Float, forecastEdgeY: Float, fullLabelHeightPx: Float): Float {
        // The shrink only relieves a collision when the forecast is the LOWER label: its bottom is
        // pinned near its bar top and the text grows upward, so shrinking moves its top down, away
        // from the actual above it. When the forecast sits ON TOP, its pinned bottom edge doesn't
        // move when shrunk — the gap to the actual below stays the same, and there's open space
        // above for the label anyway — so shrinking buys nothing and just reads as arbitrary.
        if (forecastEdgeY < actualEdgeY) return 1f
        val collisionGap = fullLabelHeightPx * (1f - FONT_SHRINK_ALLOWED_OVERLAP_FRACTION)
        return if (abs(actualEdgeY - forecastEdgeY) < collisionGap) DUAL_FORECAST_FONT_SCALE else 1f
    }

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
     *
     * This is the *baseline* push, always applied. [extraUpperPushPx] adds to it on demand.
     */
    const val DUAL_UPPER_PUSH_UP_DP = 2f

    /**
     * Separation [extraUpperPushPx] AIMS for, as a fraction of a label box height — distinct from
     * [MAX_OVERLAP_FRACTION], which only decides whether the pair is worth drawing at all.
     *
     * Placement and admission are deliberately different numbers. [MAX_OVERLAP_FRACTION] is a
     * tolerance: it says a squeezed pair still beats dropping a label. Sitting AT that tolerance is
     * not a target — a measured box is only ~0.6 visible digits tall (cap height ≈ 0.61 × box for
     * the default font), so a 0.4-of-a-box gap prints digits that genuinely overlap. That was the
     * "I don't like the overlap on Mon and Tue, there is lots of room above" report.
     *
     * 0.85 clears the digits (0.61) with ~0.24 of a box of air, while keeping the raised label near
     * enough to its own bar to still read as that bar's number.
     */
    const val DUAL_TARGET_SEPARATION_FRACTION = 0.85f

    /**
     * Ceiling on the on-demand extra raise, as a fraction of a label height. Expressed relative to
     * the label rather than in dp because what the raise has to clear IS a label; a dp constant
     * would over- or under-shoot as the graph's font scales with widget size.
     *
     * A caller with a hard ceiling of its own (the top of the bitmap, a header) should pass the
     * smaller of the two — the raise is worth skipping before it clips the label.
     */
    const val DUAL_UPPER_MAX_EXTRA_PUSH_FRACTION = 1f

    /**
     * Extra upward push (px) for the HIGHER-valued of the two labels, beyond the fixed
     * [DUAL_UPPER_PUSH_UP_DP] already in [bottomOffsetsDp]: whatever it takes to reach
     * [DUAL_TARGET_SEPARATION_FRACTION], capped at [maxExtraPushPx], and 0 when the pair is already
     * that far apart.
     *
     * Why this exists: the natural gap between the two labels IS the forecast miss, so a small miss
     * leaves them nearly on top of each other however the fixed nudges are tuned. The first symptom
     * was a dropped label (the "Mon shows no forecast high" report — short of the admission test by
     * 0.31px of 13.84); the second was two labels printed on top of each other. Both are the same
     * bug: the space directly ABOVE the upper label was sitting empty. Spending it fixes both.
     *
     * Only the upper label moves, so the pair separates and can never cross —
     * [bottomOffsetsDp]'s ordering property is preserved.
     *
     * @param currentGapPx distance between the two labels' edges with [bottomOffsetsDp] applied
     *   (same edge for both — Android passes baselines, desktop passes tops)
     * @param labelHeightPx height of a high label at the size it will be drawn
     */
    fun extraUpperPushPx(currentGapPx: Float, labelHeightPx: Float, maxExtraPushPx: Float): Float {
        val shortfall = labelHeightPx * DUAL_TARGET_SEPARATION_FRACTION - currentGapPx
        return shortfall.coerceIn(0f, maxExtraPushPx)
    }

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
