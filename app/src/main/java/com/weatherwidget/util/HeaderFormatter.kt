package com.weatherwidget.util

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Utility for formatting the widget header text (e.g., source indicator)
 * based on the available widget width to prevent overlap with navigation icons.
 */
object HeaderFormatter {
    private const val WIDTH_THRESHOLD_FULL_DAY = 330
    private const val WIDTH_THRESHOLD_SHORT_DAY = 260

    fun formatSourceIndicator(
        centerTime: LocalDateTime,
        now: LocalDateTime,
        sourceName: String,
        widthDp: Int,
        locale: Locale = Locale.getDefault()
    ): String {
        // If the graph is showing today, we only need the source indicator.
        if (centerTime.toLocalDate() == now.toLocalDate()) {
            return sourceName
        }

        val dayName = when {
            widthDp >= WIDTH_THRESHOLD_FULL_DAY -> {
                centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            }
            widthDp >= WIDTH_THRESHOLD_SHORT_DAY -> {
                centerTime.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            }
            else -> ""
        }

        return if (dayName.isNotEmpty()) {
            "$dayName • $sourceName"
        } else {
            sourceName
        }
    }
}
