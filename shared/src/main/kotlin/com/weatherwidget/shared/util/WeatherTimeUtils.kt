package com.weatherwidget.shared.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object WeatherTimeUtils {
    /**
     * The instant of a wall-clock "now". In the repeated hour of a fall-back change [now] names two
     * instants and `atZone` always takes the first, an hour early through the whole second one, so
     * the one nearer the real clock wins. Only for a [now] read from the clock: any other time in
     * that hour has no way to say which one it means. A time in a spring-forward gap moves forward.
     */
    fun liveEpochMs(
        now: LocalDateTime,
        zoneId: ZoneId = ZoneId.systemDefault(),
        clockMs: Long = System.currentTimeMillis(),
    ): Long {
        val candidates = zoneId.rules.getValidOffsets(now).map { now.toInstant(it).toEpochMilli() }
        return candidates.minByOrNull { abs(it - clockMs) } ?: now.atZone(zoneId).toInstant().toEpochMilli()
    }

    /** Start of the local hour holding [epochMs], keeping its offset across a clock change. */
    fun hourStartMs(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    fun alignToNearestHourHalfUp(dateTime: LocalDateTime): LocalDateTime {
        val truncatedHour = dateTime.truncatedTo(ChronoUnit.HOURS)
        return if (dateTime.minute >= 30) truncatedHour.plusHours(1) else truncatedHour
    }

    /**
     * An hourly window: [center] rounded to the nearest hour (half up), then [backHours] before and
     * [forwardHours] after. Every hourly graph on both platforms frames its hours this way.
     */
    fun hourWindow(center: LocalDateTime, backHours: Long, forwardHours: Long): HourSpan {
        val aligned = alignToNearestHourHalfUp(center)
        return HourSpan(aligned.minusHours(backHours), aligned.plusHours(forwardHours))
    }
}

/** Whole-hour bounds of an hourly window, both inclusive ([WeatherTimeUtils.hourWindow]). */
data class HourSpan(val start: LocalDateTime, val end: LocalDateTime)

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
     * Compact duration string: "just now", "15m", "1h 20m". [justNow] is the under-a-minute text
     * (Android passes its translated `freshness_just_now`). Both Forecast History screens use this.
     */
    fun formatDuration(durationMs: Long, justNow: String = "just now"): String {
        val minutes = (durationMs.coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1L -> justNow
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