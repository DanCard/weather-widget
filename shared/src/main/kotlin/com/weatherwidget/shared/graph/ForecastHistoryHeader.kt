package com.weatherwidget.shared.graph

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The History of Forecasts header's rules, shared by Android (`ForecastHistoryActivity`) and
 * desktop (`ForecastHistoryWindow`): the date label and how far ‹ › may page. Both headers read
 * `[←] [‹] date [›] … [source] [⟳] [⚙]`; only the widgets differ (Views vs Compose).
 *
 * Forward paging has no limit, as Android always had (desktop stopped at today + 7 until
 * 2026-10-09).
 */
object ForecastHistoryHeader {
    /** "Fri, Oct 9". */
    fun dateLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)}, " +
            "${date.month.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth}"

    /** ‹ stays enabled until [ForecastHistoryViewLogic.MAX_HISTORY_DAYS_BACK] before [today]. */
    fun canGoBack(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        date.isAfter(today.minusDays(ForecastHistoryViewLogic.MAX_HISTORY_DAYS_BACK))
}
