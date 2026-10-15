package com.weatherwidget.shared.months

import com.weatherwidget.util.NavigationUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Which daily-view `dateOffset` shows a given date, in the daily view's own terms
 * ([NavigationUtils]). Both platforms' daily views are addressed by that offset, so the months
 * view hands off through it.
 */
object MonthsDailyJump {

    /** The offset whose window holds [date] closest to its middle column (a months-view tap). */
    fun offsetCentring(date: LocalDate, today: LocalDate, numColumns: Int, skipYesterday: Boolean): Int =
        offsetCentring(date, today, skipYesterday) { numColumns }

    /**
     * As above, for a view whose column count depends on the offset ([columnsAt]) — desktop draws
     * one fewer column while its large Today overlay is on, which only happens with today in view.
     */
    fun offsetCentring(date: LocalDate, today: LocalDate, skipYesterday: Boolean, columnsAt: (offset: Int) -> Int): Int =
        candidates(date, today)
            .mapNotNull { offset -> window(offset, today, skipYesterday, columnsAt)?.let { offset to it } }
            .filter { (_, w) -> !date.isBefore(w.first) && !date.isAfter(w.second) }
            .minWithOrNull(
                compareBy<Pair<Int, Pair<LocalDate, LocalDate>>> { (offset, w) ->
                    val middle = (columnsAt(offset).coerceAtLeast(1) - 1) / 2
                    abs(ChronoUnit.DAYS.between(w.first.plusDays(middle.toLong()), date))
                }.thenBy { abs(it.first) },
            )?.first ?: 0

    /**
     * The offset whose window ends on [date] (scrolling back in from the months view keeps the right
     * edge where the months view had it). Falls back to the window that holds [date] nearest its end.
     */
    fun offsetWithRightEdge(date: LocalDate, today: LocalDate, numColumns: Int, skipYesterday: Boolean): Int =
        offsetWithRightEdge(date, today, skipYesterday) { numColumns }

    fun offsetWithRightEdge(date: LocalDate, today: LocalDate, skipYesterday: Boolean, columnsAt: (offset: Int) -> Int): Int =
        candidates(date, today)
            .mapNotNull { offset -> window(offset, today, skipYesterday, columnsAt)?.let { offset to it } }
            .filter { (_, w) -> !date.isBefore(w.first) && !date.isAfter(w.second) }
            .minWithOrNull(
                compareBy<Pair<Int, Pair<LocalDate, LocalDate>>> { (_, w) -> ChronoUnit.DAYS.between(date, w.second) }
                    .thenBy { abs(it.first) },
            )?.first ?: 0

    /** Wide enough for any daily view's column count. */
    private const val MAX_COLUMNS = 64

    private fun candidates(date: LocalDate, today: LocalDate): IntRange {
        val days = ChronoUnit.DAYS.between(today, date).toInt()
        return (days - MAX_COLUMNS - 2)..(days + 2)
    }

    private fun window(offset: Int, today: LocalDate, skipYesterday: Boolean, columnsAt: (Int) -> Int): Pair<LocalDate, LocalDate>? {
        val columns = columnsAt(offset)
        if (columns < 1) return null
        return NavigationUtils.getVisibleDateRange(today, offset, columns, skipYesterday)
    }
}
