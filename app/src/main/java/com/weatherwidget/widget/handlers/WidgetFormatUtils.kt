package com.weatherwidget.widget.handlers

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Split an hour label into its numeric part and meridiem suffix, e.g. 3pm -> ("3", "p"),
 * midnight -> ("12", "a"). The inline footer renderer draws these around the weather icon
 * (`<hour><a|p><icon>`); [formatHourLabel] joins them back for callers that want the plain string.
 */
internal fun formatHourLabelParts(time: LocalDateTime): Pair<String, String> {
    val hour = time.hour
    return when {
        hour == 0 -> "12" to "a"
        hour < 12 -> "$hour" to "a"
        hour == 12 -> "12" to "p"
        else -> "${hour - 12}" to "p"
    }
}

internal fun formatHourLabel(time: LocalDateTime): String =
    formatHourLabelParts(time).let { (hour, meridiem) -> hour + meridiem }

/**
 * Compact date label used on the hourly graph footer when zoomed out to multiple days, where a
 * bare time-of-day ("12a") can't tell you which day a region belongs to. Weekday + day-of-month,
 * e.g. 2026-06-11 -> "Wed 11". The renderer ([GraphRenderUtils.drawHourLabels]) draws this string
 * whole next to the day's weather icon, so the lack of an a/p suffix is fine.
 */
internal fun formatDateLabel(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + date.dayOfMonth

/**
 * Format a list of LocalDateTimes (hour-aligned) into a compact human-readable description
 * grouping contiguous hours into ranges. e.g. [7a, 8a, 9a, 11p] -> "7a–9a, 11p".
 */
internal fun formatMissingHourRanges(missingHours: List<LocalDateTime>): String {
    if (missingHours.isEmpty()) return ""
    val sorted = missingHours.sorted()
    val ranges = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    var rangeStart = sorted[0]
    var rangeEnd = sorted[0]
    for (i in 1 until sorted.size) {
        val current = sorted[i]
        if (current == rangeEnd.plusHours(1)) {
            rangeEnd = current
        } else {
            ranges.add(rangeStart to rangeEnd)
            rangeStart = current
            rangeEnd = current
        }
    }
    ranges.add(rangeStart to rangeEnd)
    return ranges.joinToString(", ") { (start, end) ->
        if (start == end) formatHourLabel(start)
        else "${formatHourLabel(start)}–${formatHourLabel(end)}"
    }
}

internal fun formatPrecipAmount(amountMm: Float): String =
    com.weatherwidget.shared.util.DailyRainLabels.formatPrecipAmount(amountMm)
