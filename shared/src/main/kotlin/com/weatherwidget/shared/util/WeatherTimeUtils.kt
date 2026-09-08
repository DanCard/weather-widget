package com.weatherwidget.shared.util

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object WeatherTimeUtils {
    fun alignToNearestHourHalfUp(dateTime: LocalDateTime): LocalDateTime {
        val truncatedHour = dateTime.truncatedTo(ChronoUnit.HOURS)
        return if (dateTime.minute >= 30) truncatedHour.plusHours(1) else truncatedHour
    }
}

/**
 * Standard age formatting utilities shared between platforms.
 */
object AgeFormatter {
    /**
     * Compact label for fetch-dot and graph freshness: "17m", "1h 5m".
     * Returns null if negative or if spanHours exceeds maxSpanHours.
     */
    fun formatFetchDotLabel(
        ageMinutes: Long,
        spanHours: Long,
        maxSpanHours: Long = 12L,
    ): String? {
        if (ageMinutes < 0) return null
        if (spanHours > maxSpanHours) return null
        return if (ageMinutes >= 60) {
            "${ageMinutes / 60}h${if (ageMinutes % 60 > 0) " ${ageMinutes % 60}m" else ""}"
        } else {
            "${ageMinutes}m"
        }
    }

    /**
     * Compact duration string: "just now", "15m", "1h 20m".
     */
    fun formatDuration(durationMs: Long): String {
        val minutes = (durationMs.coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "${minutes}m"
            else -> {
                val h = minutes / 60L
                val m = minutes % 60L
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
        }
    }

    /**
     * Stale diagnostic age label: "15m old", "2h 10m old", "1d 4h old".
     */
    fun formatAgeOld(ageMillis: Long): String {
        val minutes = (ageMillis.coerceAtLeast(0L)) / 60_000L
        val hours = minutes / 60L
        return when {
            hours >= 24L -> "${hours / 24L}d ${hours % 24L}h old"
            hours > 0L -> "${hours}h ${minutes % 60L}m old"
            else -> "${minutes}m old"
        }
    }
}

/** Format latitude/longitude coordinate with standard 4 decimals. */
fun formatCoord(value: Double): String = "%.4f".format(value)

/** Format personal station discount percentage description for settings. */
fun formatPersonalStationDiscount(percent: Int): String = when (percent) {
    0 -> "0% — no discount (counts the same as official)"
    100 -> "100% — personal stations ignored"
    else -> "$percent% discount"
}