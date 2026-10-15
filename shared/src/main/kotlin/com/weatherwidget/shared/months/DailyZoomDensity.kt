package com.weatherwidget.shared.months

/**
 * The one rule for "the daily columns are too dense — show the months line graph instead".
 * Desktop's scroll-wheel zoom-out and Android's column-view zoom-out pinch both ask it, so the
 * hand-off happens at the same column width everywhere.
 */
object DailyZoomDensity {
    /**
     * Narrowest day column worth drawing. Below this the triple bar and its labels stop fitting.
     * Starting value; tune on screen.
     */
    const val MIN_DAY_WIDTH_DP = 28f

    fun tooDense(graphWidthDp: Float, columns: Int): Boolean =
        columns > 0 && graphWidthDp / columns < MIN_DAY_WIDTH_DP

    /** Most columns that still fit [graphWidthDp]; at least 1. */
    fun maxColumns(graphWidthDp: Float): Int =
        (graphWidthDp / MIN_DAY_WIDTH_DP).toInt().coerceAtLeast(1)
}
