package com.weatherwidget.shared.graph

/**
 * Shared sizing constants for daily view layouts across Android widget and desktop.
 */
object DailyLayoutConstants {
    /**
     * Per-column width assumption (dp) on widgets narrower than [WIDE_LAYOUT_MIN_WIDTH_DP]. Tighter
     * than [WIDE_CELL_WIDTH_DP] because a narrow widget loses a bigger share of its width to fixed
     * costs — Today's double slot, margins, nav arrows — so one more column matters most there: the
     * Fold 4 cover (~337dp) gets 7 slots (6 days) instead of 6 (plans/261016-fold4-cover-display-six-columns.md).
     */
    const val NARROW_CELL_WIDTH_DP = 55

    /**
     * Per-column width assumption (dp) from [WIDE_LAYOUT_MIN_WIDTH_DP] up. 55dp everywhere made the
     * Fold 4 inner widget (~662dp) 13 slots, too dense (plans/261017-daily-density-narrow-only.md).
     */
    const val WIDE_CELL_WIDTH_DP = 60

    /**
     * Width (dp) at which [cellWidthDp] switches to [WIDE_CELL_WIDTH_DP]. Above any phone's width.
     * The switch must sit where both cell widths give the same column count, or a wider widget would
     * show fewer columns: 420–438dp for Android's fit bias (30dp), 435–453dp for desktop's (15dp).
     * 436 is in both; the monotonic-columns tests on each platform pin it.
     */
    const val WIDE_LAYOUT_MIN_WIDTH_DP = 436

    /** Per-column width (dp) used to turn a daily layout's width into a column count. */
    fun cellWidthDp(widthDp: Int): Int =
        if (widthDp < WIDE_LAYOUT_MIN_WIDTH_DP) NARROW_CELL_WIDTH_DP else WIDE_CELL_WIDTH_DP

    /** Per-row height assumption (dp). */
    const val CELL_HEIGHT_DP = 90

    /** Width threshold below which a layout is treated as single-icon width (no graph). */
    const val ICON_WIDTH_THRESHOLD_DP = 130
}
