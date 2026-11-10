package com.weatherwidget.shared.graph

/**
 * The daily column's LEFT slot — the forecast from a day earlier — on every column type, both
 * platforms. Today and past columns always own one (their triple bar); a future column owns one
 * only with the opt-in "forecast from 24 h ago" setting and a qualifying pick
 * ([com.weatherwidget.shared.daily.DailyColumn.futurePriorBarDrawn],
 * plans/261110-future-days-prior-forecast-bar.md).
 *
 * The future bar keeps its own centred [DailyBarWidths.forecastPx] bar and gains a thinner
 * [DailyBarWidths.pastPx] bar touching it on the left, so the live forecast stays the dominant mark.
 */
object PriorBarSlot {

    /** True when the column draws a left bar — the rule the y-axis and the rain-label cap read. */
    fun drawn(isToday: Boolean, isPast: Boolean, futurePriorBarDrawn: Boolean): Boolean =
        isToday || isPast || futurePriorBarDrawn

    /**
     * The column's single high label gets the thin black outline: whenever a left bar is drawn, since
     * a taller "forecast from a day earlier" runs up through the centred label (Fold 4, Fri 2026-11-13:
     * a 75° prior bar crossed the 67° label). Future columns without one stay plain.
     */
    fun highLabelOutlined(isToday: Boolean, isPast: Boolean, futurePriorBarDrawn: Boolean): Boolean =
        drawn(isToday, isPast, futurePriorBarDrawn)

    /** Width of a future column's left bar: the past-day width, visibly secondary to the forecast bar. */
    fun futureWidthPx(widths: DailyBarWidths): Float = widths.pastPx

    /**
     * Centre-to-centre distance from a future column's forecast bar to its left bar: the two touch
     * ([TodayColumnHighlight.tripleBarSpacing]), clamped inside the column.
     */
    fun futureOffsetPx(widths: DailyBarWidths, dayWidthPx: Float, columnEdgeMarginPx: Float): Float =
        TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = widths.forecastPx,
            flankBarWidthPx = futureWidthPx(widths),
            dayWidthPx = dayWidthPx,
            columnEdgeMarginPx = columnEdgeMarginPx,
        )

    /** Width of the left bar on a column of this kind (its round cap reaches half of it above the high). */
    fun widthPx(isToday: Boolean, widths: DailyBarWidths): Float =
        if (isToday) widths.todayPx else widths.pastPx
}
