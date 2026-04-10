package com.weatherwidget.widget.handlers

import java.time.LocalDateTime
import java.util.Locale

internal fun formatHourLabel(time: LocalDateTime): String {
    val hour = time.hour
    return when {
        hour == 0 -> "12a"
        hour < 12 -> "${hour}a"
        hour == 12 -> "12p"
        else -> "${hour - 12}p"
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
