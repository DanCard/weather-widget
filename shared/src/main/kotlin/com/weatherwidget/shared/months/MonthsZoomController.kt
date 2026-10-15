package com.weatherwidget.shared.months

import java.time.LocalDate

/** What a zoom gesture should do; platforms apply it to their own state. */
sealed interface MonthsZoomAction {
    /** Daily view: prepend one more history column. */
    data object AddHistoryColumn : MonthsZoomAction

    /** Daily view → months view, at [span], right edge on [endDate]. */
    data class OpenMonths(val span: MonthsSpan, val endDate: LocalDate) : MonthsZoomAction

    /** Months view: change span, keeping the right edge. */
    data class SetSpan(val span: MonthsSpan) : MonthsZoomAction

    /** Months view → daily view with its right edge on [rightEdge] (zoom back in). */
    data class BackToDaily(val rightEdge: LocalDate) : MonthsZoomAction

    /** Months view → daily view centred on [date] (a tap or click). */
    data class OpenDaily(val date: LocalDate) : MonthsZoomAction

    data object None : MonthsZoomAction
}

/**
 * The daily ↔ months zoom state machine, shared so the same gestures land in the same place on
 * Android and desktop. Pure: callers pass what they know and apply the returned action.
 *
 * Out:  daily +1 column … (too dense, or no more history) → months ONE → TWO → SIX → TWELVE.
 * In:   TWELVE → SIX → TWO → ONE → daily (right edge kept).
 * Tap:  months → daily centred on the tapped day.
 */
object MonthsZoomController {

    /**
     * Zoom out in the daily view.
     * @param graphWidthDp width the day columns share
     * @param columns columns shown now
     * @param canAddColumn whether the daily view has more history to prepend (data and its own cap)
     * @param rightEdge the daily view's last visible date
     */
    fun dailyZoomOut(graphWidthDp: Float, columns: Int, canAddColumn: Boolean, rightEdge: LocalDate): MonthsZoomAction =
        if (canAddColumn && !DailyZoomDensity.tooDense(graphWidthDp, columns + 1)) {
            MonthsZoomAction.AddHistoryColumn
        } else {
            MonthsZoomAction.OpenMonths(MonthsSpan.ONE, rightEdge)
        }

    fun monthsZoomOut(span: MonthsSpan): MonthsZoomAction =
        span.wider()?.let { MonthsZoomAction.SetSpan(it) } ?: MonthsZoomAction.None

    fun monthsZoomIn(span: MonthsSpan, window: MonthsWindow): MonthsZoomAction =
        span.narrower()?.let { MonthsZoomAction.SetSpan(it) } ?: MonthsZoomAction.BackToDaily(window.end)

    fun monthsTap(date: LocalDate): MonthsZoomAction = MonthsZoomAction.OpenDaily(date)
}
