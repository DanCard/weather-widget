package com.weatherwidget.shared.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Compact hour/date label formatting used by the Android widget's hourly footer.
 *
 * [hourLabel] emits the one-character meridiem form ("3p") drawn beside the weather icon;
 * [RainAnalyzer.formatHour] is the two-character form ("3pm") and delegates to [hourLabel].
 */
object HourLabelFormatter {
    /** Split an hour into its numeric part and meridiem suffix, e.g. 3pm -> ("3", "p"). */
    fun hourLabelParts(time: LocalDateTime): Pair<String, String> = hourLabelParts(time.hour)

    /** [hourLabelParts] for a raw 0-23 hour. */
    fun hourLabelParts(hour: Int): Pair<String, String> = when {
        hour == 0 -> "12" to "a"
        hour < 12 -> "$hour" to "a"
        hour == 12 -> "12" to "p"
        else -> "${hour - 12}" to "p"
    }

    fun hourLabel(time: LocalDateTime): String = hourLabel(time.hour)

    /** Short label for a raw 0-23 hour: "12a", "1p", etc. */
    fun hourLabel(hour: Int): String =
        hourLabelParts(hour).let { (h, meridiem) -> h + meridiem }

    /**
     * Compact date label used on the hourly graph footer when zoomed out to multiple days.
     * Weekday + day-of-month, e.g. 2026-06-11 -> "Wed 11".
     */
    fun dateLabel(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + date.dayOfMonth

    /**
     * Format a list of LocalDateTimes (hour-aligned) into a compact human-readable description
     * grouping contiguous hours into ranges. e.g. [7a, 8a, 9a, 11p] -> "7a–9a, 11p".
     */
    fun missingHourRanges(missingHours: List<LocalDateTime>): String {
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
            if (start == end) hourLabel(start)
            else "${hourLabel(start)}–${hourLabel(end)}"
        }
    }
}
