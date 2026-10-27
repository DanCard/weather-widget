package com.weatherwidget.shared.graph

/**
 * Stroke widths of the daily view's temperature bars — one rule for the Android widget and the
 * desktop popup. Each platform supplies its px-per-dp (Android: density × scaleFactor × bitmap label
 * scale; desktop: density × popup scale) and the column width; this object owns the dp values, the
 * past-day ratio, and the per-column cap.
 *
 * Three widths:
 *  - [forecastPx] — a future day's single bar.
 *  - [todayPx] — each of today's three bars (yesterday's forecast | thermostat | live forecast).
 *  - [pastPx] — each of a past day's three bars, [PAST_WIDTH_SCALE] of today's, so history reads as
 *    distinctly lighter than today.
 */
data class DailyBarWidths(
    val forecastPx: Float,
    val todayPx: Float,
    val pastPx: Float,
) {
    companion object {
        /** Future-day single bar, in dp. */
        const val FORECAST_BAR_WIDTH_DP = 9f

        /** Today's triple bar, in dp, when today's column is the normal width. */
        const val TODAY_BAR_WIDTH_DP = 9f

        /**
         * Today's triple bar, in dp, when the large Today overlay widens today's column — its extra
         * labels need the room between the bars.
         */
        const val TODAY_BAR_WIDTH_DP_WIDENED = 8f

        /**
         * Past days' triple bar as a fraction of today's. 0.8 from 2026-10-05
         * (plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md); narrowed to 0.65 on
         * 2026-10-27, with today's bars +1 dp, to set history further apart from today.
         */
        const val PAST_WIDTH_SCALE = 0.65f

        /** No single bar exceeds this fraction of a regular column's width (narrow-column guard). */
        const val MAX_COLUMN_FRACTION = 0.22f

        fun resolve(pxPerDp: Float, dayWidthPx: Float, todayWidened: Boolean): DailyBarWidths {
            val cap = dayWidthPx * MAX_COLUMN_FRACTION
            val todayDp = if (todayWidened) TODAY_BAR_WIDTH_DP_WIDENED else TODAY_BAR_WIDTH_DP
            val today = (todayDp * pxPerDp).coerceAtMost(cap)
            return DailyBarWidths(
                forecastPx = (FORECAST_BAR_WIDTH_DP * pxPerDp).coerceAtMost(cap),
                todayPx = today,
                pastPx = today * PAST_WIDTH_SCALE,
            )
        }
    }
}
