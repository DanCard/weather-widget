package com.weatherwidget.shared.graph

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Forecast Comparison header's rules, shared by Android (`ForecastComparisonActivity`) and
 * desktop (`ForecastComparisonWindow`): the date label and how far ‹ › may page. Both headers read
 * `[←] [‹] date [›] … [source] [⟳] [⚙]`; only the widgets differ (Views vs Compose).
 *
 * Forward paging has no limit, as Android always had (desktop stopped at today + 7 until
 * 2026-10-09).
 */
object ForecastComparisonHeader {
    /** "Fri, Oct 9". */
    fun dateLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)}, " +
            "${date.month.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth}"

    /** ‹ stays enabled until [ForecastComparisonViewLogic.MAX_HISTORY_DAYS_BACK] before [today]. */
    fun canGoBack(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        date.isAfter(today.minusDays(ForecastComparisonViewLogic.MAX_HISTORY_DAYS_BACK))
}
