package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log

/**
 * Vertical geometry under the daily bars, shared by the Android widget and desktop: each column's
 * weather icon hangs [BAR_ICON_GAP_DP] below its lowest bar, the low label sits directly under the
 * icon, and the whole stack stays above the day-name row. [graphBottom] reserves exactly that stack
 * below the coldest bar, so the bars use every pixel the labels don't.
 *
 * Each platform measures its own text and picks its own icon size; the gaps and the arithmetic live
 * here (plans/261029-shared-daily-bottom-stack.md).
 */
data class DailyBottomStack(
    val canvasHeightPx: Float,
    /** dp→px for the gaps, including the view's label scale. */
    val pxPerDp: Float,
    val iconSizePx: Float,
    /** Height of the tallest low label (Android: the temp-label text size). */
    val lowLabelHeightPx: Float,
    val dayLabelHeightPx: Float,
    /** Space below the day-name row. */
    val dayLabelBottomMarginPx: Float,
    /** The graph never ends above this, however tall the stack. */
    val graphBottomFloorPx: Float,
    /** Blank line-box space under the low label's digits (they have no descenders); 0 = none measured. */
    val lowLabelBlankBelowPx: Float = 0f,
    /** Blank line-box space above the day name's capitals; 0 = none measured. */
    val dayLabelBlankAbovePx: Float = 0f,
) {
    val barIconGapPx: Float get() = BAR_ICON_GAP_DP * pxPerDp
    val iconLowGapPx: Float get() = ICON_LOW_GAP_DP * pxPerDp
    val lowDayGapPx: Float get() = LOW_DAY_GAP_DP * pxPerDp

    /**
     * How far the low label's line box may run into the day name's: the blank space on both sides of
     * the line-box gap, less what keeps [LOW_DAY_INK_GAP_DP] of visible gap between the two inks.
     */
    val reclaimPx: Float get() =
        (lowLabelBlankBelowPx + dayLabelBlankAbovePx - (LOW_DAY_INK_GAP_DP - LOW_DAY_GAP_DP) * pxPerDp)
            .coerceAtLeast(0f)

    /** Bar end to the bottom of the low label, plus the gap above the day names, less [reclaimPx]. */
    val stackHeightPx: Float get() =
        barIconGapPx + iconSizePx + iconLowGapPx + lowLabelHeightPx + lowDayGapPx - reclaimPx

    val dayLabelTop: Float get() = canvasHeightPx - dayLabelBottomMarginPx - dayLabelHeightPx

    val requestedGraphBottom: Float get() = dayLabelTop - stackHeightPx

    /** Lowest point a bar may reach. */
    val graphBottom: Float get() = requestedGraphBottom.coerceAtLeast(graphBottomFloorPx)

    /** Band below the bars that routes taps to the day's icon (desktop). */
    val tapStripHeightPx: Float get() = canvasHeightPx - graphBottom

    /**
     * Icon top under a bar ending at [barBottomY], held up so the icon and a low label of
     * [lowLabelHeightPx] still clear the day names when the graph floor pushed the bar into the stack.
     */
    fun iconTop(barBottomY: Float, lowLabelHeightPx: Float = this.lowLabelHeightPx): Float =
        (barBottomY + barIconGapPx)
            .coerceAtMost(dayLabelTop - lowDayGapPx + reclaimPx - lowLabelHeightPx - iconLowGapPx - iconSizePx)

    fun lowLabelTop(iconTop: Float): Float = iconTop + iconSizePx + iconLowGapPx

    fun describe(): String =
        "canvasH=${canvasHeightPx.r()} icon=${iconSizePx.r()} lowLabelH=${lowLabelHeightPx.r()} " +
            "dayLabelH=${dayLabelHeightPx.r()} blankBelow=${lowLabelBlankBelowPx.r()} " +
            "blankAbove=${dayLabelBlankAbovePx.r()} reclaim=${reclaimPx.r()} stack=${stackHeightPx.r()} dayLabelTop=${dayLabelTop.r()} " +
            "graphBottom=${graphBottom.r()} floored=${requestedGraphBottom < graphBottomFloorPx}"

    companion object {
        private const val TAG = "DailyBottomStack"
        const val BAR_ICON_GAP_DP = 3f
        const val ICON_LOW_GAP_DP = 0f
        const val LOW_DAY_GAP_DP = 1f

        /** Least visible gap between the low label's digits and the day name's capitals. */
        const val LOW_DAY_INK_GAP_DP = 3f

        /** Builds the stack and traces it (VERBOSE: once per daily render). */
        fun resolve(
            canvasHeightPx: Float,
            pxPerDp: Float,
            iconSizePx: Float,
            lowLabelHeightPx: Float,
            dayLabelHeightPx: Float,
            dayLabelBottomMarginPx: Float,
            graphBottomFloorPx: Float,
            platform: String,
            lowLabelBlankBelowPx: Float = 0f,
            dayLabelBlankAbovePx: Float = 0f,
        ): DailyBottomStack =
            DailyBottomStack(
                canvasHeightPx, pxPerDp, iconSizePx, lowLabelHeightPx, dayLabelHeightPx,
                dayLabelBottomMarginPx, graphBottomFloorPx, lowLabelBlankBelowPx, dayLabelBlankAbovePx,
            ).also { stack -> Log.v(TAG) { "$platform ${stack.describe()}" } }

        private fun Float.r(): String = "%.1f".format(this)
    }
}
