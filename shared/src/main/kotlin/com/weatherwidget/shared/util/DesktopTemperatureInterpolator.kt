package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object DesktopTemperatureInterpolator {
    const val INTERPOLATION_THRESHOLD = 0.1f

    fun getInterpolatedTemperature(
        hourlyForecasts: List<HourlyForecast>,
        targetEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Float? {
        if (hourlyForecasts.isEmpty()) return null

        val sorted = hourlyForecasts.sortedBy { it.dateTime }
        val targetTime = Instant.ofEpochMilli(targetEpochMs).atZone(zoneId).toLocalDateTime()
        val targetHour = targetTime.truncatedTo(ChronoUnit.HOURS)
        val nextHour = targetHour.plusHours(1)
        val targetHourMs = targetHour.atZone(zoneId).toInstant().toEpochMilli()
        val nextHourMs = nextHour.atZone(zoneId).toInstant().toEpochMilli()

        val current = sorted.find { it.dateTime == targetHourMs }
        val next = sorted.find { it.dateTime == nextHourMs }

        if (current != null && next == null) return current.temperature
        if (current == null && next != null) return next.temperature
        if (current == null && next == null) {
            return sorted.minByOrNull { abs(it.dateTime - targetEpochMs) }?.temperature
        }

        val currentTemp = current!!.temperature
        val tempDiff = next!!.temperature - currentTemp
        if (abs(tempDiff) < INTERPOLATION_THRESHOLD) return currentTemp

        val factor = targetTime.minute / 60.0f
        return currentTemp + tempDiff * factor
    }

    fun getUpdatesPerHour(hourlyForecasts: List<HourlyForecast>): Int {
        if (hourlyForecasts.size < 2) return 1
        val maxDiff = hourlyForecasts
            .sortedBy { it.dateTime }
            .zipWithNext()
            .maxOf { abs(it.second.temperature - it.first.temperature) }
        return when {
            maxDiff >= 8f -> 4
            maxDiff >= 4f -> 2
            else -> 1
        }
    }
}
