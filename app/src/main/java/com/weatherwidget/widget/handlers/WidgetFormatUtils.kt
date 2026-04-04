package com.weatherwidget.widget.handlers

import java.time.LocalDateTime

internal fun formatHourLabel(time: LocalDateTime): String {
    val hour = time.hour
    return when {
        hour == 0 -> "12a"
        hour < 12 -> "${hour}a"
        hour == 12 -> "12p"
        else -> "${hour - 12}p"
    }
}
