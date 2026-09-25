package com.weatherwidget.shared.graph

/**
 * Dash pattern for a daily-view bar that stands in for data the column doesn't really have:
 *  - a past day whose actuals were measured at a previous site (`PreviousSiteHistory`), and
 *  - today's "yesterday's forecast" bar when that forecast was fetched more than
 *    [com.weatherwidget.shared.util.DailySnapshotSelector.STALE_AFTER_HOURS] ago.
 *
 * Dashed rather than dimmed: brightness and grey already mean cloud cover in this view (user
 * decision 2026-09-25). No glyph either — the column position says which case it is.
 *
 * Intervals are multiples of the stroke width, so thin today bars and wide history bars read the
 * same. Both platforms draw these bars with ROUND caps, which grow each dash by one stroke width
 * and shrink each gap by the same, so the visible dash is `ON + 1` widths and the gap `OFF - 1`.
 */
object StandInBarStyle {
    const val DASH_ON_WIDTHS = 1.2f
    const val DASH_OFF_WIDTHS = 1.9f

    fun intervals(strokeWidth: Float): FloatArray =
        floatArrayOf(strokeWidth * DASH_ON_WIDTHS, strokeWidth * DASH_OFF_WIDTHS)
}
