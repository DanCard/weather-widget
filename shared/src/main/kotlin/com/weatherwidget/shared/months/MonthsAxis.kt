package com.weatherwidget.shared.months

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** An x-axis tick: [major] on the 1st of a month (gridline + month label), minor inside a month. */
data class MonthsTick(val date: LocalDate, val label: String?, val major: Boolean)

object MonthsAxis {

    /**
     * Month ticks on the 1st, labelled "Oct"; the first labelled month and every January carry the
     * year ("Jan 2026"). One month also gets day ticks on the 8th/15th/22nd; two months a bare tick
     * on the 15th.
     */
    fun ticks(window: MonthsWindow, span: MonthsSpan, locale: Locale = Locale.getDefault()): List<MonthsTick> {
        val ticks = mutableListOf<MonthsTick>()
        var yearShown = false
        var month = window.start.withDayOfMonth(1)
        while (!month.isAfter(window.end)) {
            if (month in window) {
                val name = month.month.getDisplayName(TextStyle.SHORT, locale)
                val withYear = !yearShown || month.month == Month.JANUARY
                ticks += MonthsTick(month, if (withYear) "$name ${month.year}" else name, major = true)
                yearShown = true
            }
            val minorDays = when (span) {
                MonthsSpan.ONE -> listOf(8, 15, 22)
                MonthsSpan.TWO -> listOf(15)
                else -> emptyList()
            }
            for (day in minorDays) {
                val date = month.withDayOfMonth(day)
                if (date in window) {
                    ticks += MonthsTick(date, if (span == MonthsSpan.ONE) day.toString() else null, major = false)
                }
            }
            month = month.plusMonths(1)
        }
        return ticks.sortedBy { it.date }
    }
}
