package com.weatherwidget.widget.handlers

import java.time.LocalDateTime
import java.util.Locale

/**
 * Split an hour label into its numeric part and meridiem suffix, e.g. 3pm -> ("3", "p"),
 * midnight -> ("12", "a"). The inline footer renderer draws these around the weather icon
 * (`<hour><icon><a|p>`); [formatHourLabel] joins them back for callers that want the plain string.
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

internal fun formatPrecipAmount(amountMm: Float): String {
    val country = Locale.getDefault().country.uppercase(Locale.US)
    return if (country == "US" || country == "GB") {
        formatInches(amountMm / 25.4f)
    } else {
        formatMillimeters(amountMm)
    }
}

private fun formatInches(amountInches: Float): String {
    val precision = when {
        amountInches < 0.01f -> 3
        amountInches < 0.1f -> 3
        amountInches < 1f -> 2
        else -> 1
    }
    val formatted = String.format(Locale.US, "%.${precision}f", amountInches)
        .trimEnd('0')
        .trimEnd('.')
    return "${formatted.removePrefix("0")}in"
}

private fun formatMillimeters(amountMm: Float): String {
    val precision = if (amountMm < 10f) 1 else 0
    val formatted = String.format(Locale.US, "%.${precision}f", amountMm)
        .trimEnd('0')
        .trimEnd('.')
    return "${formatted.removePrefix("0")}mm"
}
