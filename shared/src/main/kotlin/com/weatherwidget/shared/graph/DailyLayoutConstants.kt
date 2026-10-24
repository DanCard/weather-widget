package com.weatherwidget.shared.graph

import kotlin.math.roundToInt

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
     * show fewer columns: 420–438dp for [COLUMN_FIT_BIAS_DP] (30dp). The monotonic-columns tests on
     * each platform pin it.
     */
    const val WIDE_LAYOUT_MIN_WIDTH_DP = 436

    /** Per-column width (dp) used to turn a daily layout's width into a column count. */
    fun cellWidthDp(widthDp: Int): Int =
        if (widthDp < WIDE_LAYOUT_MIN_WIDTH_DP) NARROW_CELL_WIDTH_DP else WIDE_CELL_WIDTH_DP

    /** Per-row height assumption (dp). */
    const val CELL_HEIGHT_DP = 90

    /**
     * Bias added to the width before dividing by [cellWidthDp] (55dp below 436dp, 60dp from there).
     *
     * Combined with round-to-nearest (not floor), this rounds UP a layout that is within ~(this
     * many) dp of the next column, fitting one more forecast day. The 5→6 boundary lands at width
     * >= 273dp, 6→7 at >= 328dp, 7→8 at >= 383dp, 9→10 at >= 540dp and 11→12 at >= 660dp.
     *
     * Density is governed mainly by the cell width; this bias just nudges borderline widths up.
     * Representative results: Samsung Fold 4 cover (~337dp) → 7 columns (6 days with large Today
     * overlay), Pixel 7 Pro (~373dp) → 7, Samsung Fold 4 full-width (~574dp) → 10, the Fold 4 inner
     * widget at ~662dp → 12 (11 days).
     * Days shown == columns (see NavigationUtils.getDayOffsets), minus one when the detailed Today
     * overlay spans two slots.
     *
     * Data backing the rightmost columns: at >8 columns the narrow skip-yesterday rule is off
     * (NavigationUtils.NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD), so a 10-column window is
     * yesterday..+8. Every fetch requests ForecastHorizon.MAX_DAYS, so Open-Meteo covers
     * through today+15 while NWS reaches ~today+6; columns past a source's real coverage
     * render the climate-normal filler, which is the intended terminal state.
     *
     * One value for the widget and the desktop popup: desktop kept the old 15dp after Android moved
     * to 30 (2026-06-20, so the Fold full-width widget reached 9 days) and showed a day fewer near
     * each boundary.
     */
    const val COLUMN_FIT_BIAS_DP = 30

    /**
     * Row-count analogue of [COLUMN_FIT_BIAS_DP]: biases height toward the next row so a
     * layout that is nearly tall enough is treated as the taller one.
     */
    const val ROW_FIT_BIAS_DP = 25

    /**
     * Day columns a daily layout [widthDp] wide shows, at most [maxColumns] (the desktop popup caps
     * at 9; the widget has no cap).
     */
    fun columnsForWidthDp(widthDp: Int, maxColumns: Int = Int.MAX_VALUE): Int =
        ((widthDp + COLUMN_FIT_BIAS_DP).toFloat() / cellWidthDp(widthDp))
            .roundToInt()
            .coerceIn(1, maxColumns.coerceAtLeast(1))

    /** Rows a layout [heightDp] tall has, biased toward the taller layout ([ROW_FIT_BIAS_DP]). */
    fun rowsForHeightDp(heightDp: Int): Int =
        ((heightDp + ROW_FIT_BIAS_DP).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

    /** Width threshold below which a layout is treated as single-icon width (no graph). */
    const val ICON_WIDTH_THRESHOLD_DP = 130
}
