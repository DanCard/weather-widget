package com.weatherwidget.shared.util

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Formats the widget header's source indicator (e.g. "Wednesday • NWS") based on available width.
 *
 * Pure Kotlin; both platforms' hourly headers reach it through [HeaderContent]. The daily header
 * shows the bare short label (its date is drawn beside the buttons).
 */
object HeaderFormatter {
    private const val WIDTH_THRESHOLD_FULL_DAY = 380
    private const val WIDTH_THRESHOLD_SHORT_DAY = 300

    fun formatSourceIndicator(
        centerTime: LocalDateTime,
        now: LocalDateTime,
        sourceName: String,
        widthDp: Int,
        locale: Locale = Locale.getDefault(),
    ): String {
        // If the graph is showing today, we only need the source indicator.
        if (centerTime.toLocalDate() == now.toLocalDate()) {
            return sourceName
        }

        val dayName = when {
            widthDp >= WIDTH_THRESHOLD_FULL_DAY -> centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            widthDp >= WIDTH_THRESHOLD_SHORT_DAY -> centerTime.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> ""
        }

        return if (dayName.isNotEmpty()) {
            "$dayName • $sourceName"
        } else {
            sourceName
        }
    }
}
