package com.weatherwidget.shared.months

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.roundToInt

/** The inclusive date range the months view shows. */
data class MonthsWindow(val start: LocalDate, val end: LocalDate) {
    val dayCount: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/**
 * Window, pan and hit-testing for the months view. The view is addressed by its right-edge date
 * ([MonthsWindow.end]); the span sets how far back the left edge sits.
 */
object MonthsViewport {

    fun window(span: MonthsSpan, endDate: LocalDate): MonthsWindow =
        MonthsWindow(endDate.minusMonths(span.months.toLong()).plusDays(1), endDate)

    /**
     * Where the view opens with no other anchor: the end of the data (the forecast tail), so today
     * sits near the right edge with history to its left.
     */
    fun defaultEnd(today: LocalDate, bounds: ClosedRange<LocalDate>?): LocalDate =
        bounds?.endInclusive?.takeIf { it.isAfter(today) } ?: today

    /**
     * Keeps the window over data: the right edge never passes the last data day, and the left edge
     * never passes the first one — unless the data is shorter than the span, in which case the
     * window ends at the last day and starts before the data.
     */
    fun clampEnd(endDate: LocalDate, span: MonthsSpan, bounds: ClosedRange<LocalDate>?): LocalDate {
        if (bounds == null) return endDate
        var end = if (endDate.isAfter(bounds.endInclusive)) bounds.endInclusive else endDate
        if (window(span, end).start.isBefore(bounds.start)) {
            // Smallest end whose window starts on the first data day, capped at the last data day.
            val minEnd = bounds.start.plusMonths(span.months.toLong()).minusDays(1)
            end = if (minEnd.isAfter(bounds.endInclusive)) bounds.endInclusive else minEnd
        }
        return end
    }

    fun pan(endDate: LocalDate, deltaDays: Int, span: MonthsSpan, bounds: ClosedRange<LocalDate>?): LocalDate =
        clampEnd(endDate.plusDays(deltaDays.toLong()), span, bounds)

    /** Whole days a horizontal drag of [dragPx] moves the view; dragging right shows earlier days. */
    fun dragDays(dragPx: Float, plotWidthPx: Float, window: MonthsWindow): Int {
        if (plotWidthPx <= 0f) return 0
        val dayWidth = plotWidthPx / window.dayCount
        return -(dragPx / dayWidth).roundToInt()
    }

    /** Centre of [date]'s slot. */
    fun xForDate(date: LocalDate, window: MonthsWindow, plotLeft: Float, plotWidth: Float): Float {
        val index = ChronoUnit.DAYS.between(window.start, date).toFloat()
        return plotLeft + plotWidth * (index + 0.5f) / window.dayCount
    }

    /** The day under [x]; positions outside the plot snap to the nearest edge day. */
    fun dateAtX(x: Float, window: MonthsWindow, plotLeft: Float, plotWidth: Float): LocalDate {
        if (plotWidth <= 0f) return window.start
        val index = floor((x - plotLeft) / plotWidth * window.dayCount).toInt()
            .coerceIn(0, window.dayCount - 1)
        return window.start.plusDays(index.toLong())
    }
}
