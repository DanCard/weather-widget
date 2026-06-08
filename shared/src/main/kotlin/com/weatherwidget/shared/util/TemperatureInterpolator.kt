package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object TemperatureInterpolator {
    const val INTERPOLATION_THRESHOLD = 0.1f

    fun getInterpolatedTemperature(
        hourlyForecasts: List<HourlyForecast>,
        targetEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Float? {
        if (hourlyForecasts.isEmpty()) return null

        val sorted = hourlyForecasts.sortedBy { it.dateTime }
        val prev = sorted.lastOrNull { it.dateTime <= targetEpochMs }
        val next = sorted.firstOrNull { it.dateTime >= targetEpochMs }

        if (prev == null && next == null) return null
        if (prev != null && next == null) return prev.temperature
        if (prev == null && next != null) return next.temperature

        if (prev!!.dateTime == targetEpochMs) return prev.temperature
        if (next!!.dateTime == targetEpochMs) return next.temperature

        val timeDiff = next.dateTime - prev.dateTime
        if (timeDiff <= 3 * 3600 * 1000L) {
            val targetOffset = targetEpochMs - prev.dateTime
            val tempDiff = next.temperature - prev.temperature
            if (abs(tempDiff) < INTERPOLATION_THRESHOLD) return prev.temperature
            val factor = targetOffset.toFloat() / timeDiff
            return prev.temperature + tempDiff * factor
        } else {
            return if (targetEpochMs - prev.dateTime <= next.dateTime - targetEpochMs) {
                prev.temperature
            } else {
                next.temperature
            }
        }
    }

    fun getUpdatesPerHour(tempDifference: Number): Int {
        val absDiff = abs(tempDifference.toFloat())
        return when {
            absDiff >= 6f -> 4
            absDiff >= 4f -> 3
            absDiff >= 2f -> 2
            else -> 1
        }
    }

    fun getUpdatesPerHour(hourlyForecasts: List<HourlyForecast>): Int {
        if (hourlyForecasts.size < 2) return 1
        val sorted = hourlyForecasts.sortedBy { it.dateTime }
        val maxDiff = sorted.zipWithNext().maxOf { abs(it.second.temperature - it.first.temperature) }
        return when {
            maxDiff >= 8f -> 4
            maxDiff >= 4f -> 2
            else -> 1
        }
    }

    fun smoothValuesPreservingAllExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values

        val intValues = values.map { it.roundToInt() }
        val maxima = findLocalExtremaIndices(intValues, isMax = true)
        val minima = findLocalExtremaIndices(intValues, isMax = false)
        
        val preservedIndices = mutableSetOf(0, values.lastIndex)
        preservedIndices.addAll(maxima)
        preservedIndices.addAll(minima)

        var current = values
        repeat(iterations) {
            val smoothed = MutableList(current.size) { 0f }
            for (i in current.indices) {
                val prev = if (i > 0) current[i - 1] else current[i]
                val curr = current[i]
                val next = if (i < current.lastIndex) current[i + 1] else current[i]
                smoothed[i] = prev * 0.25f + curr * 0.5f + next * 0.25f
            }
            preservedIndices.forEach { smoothed[it] = values[it] }
            current = smoothed
        }
        return current
    }

    private fun findLocalExtremaIndices(
        values: List<Int>,
        isMax: Boolean,
    ): Set<Int> {
        if (values.size < 3) return emptySet()

        val extrema = mutableSetOf<Int>()
        var i = 1
        while (i < values.lastIndex) {
            val current = values[i]
            val prev = values[i - 1]

            val isPotential = if (isMax) current > prev else current < prev

            if (isPotential) {
                var j = i
                while (j < values.lastIndex && values[j + 1] == current) {
                    j++
                }

                if (j < values.lastIndex) {
                    val next = values[j + 1]
                    val isExtremum = if (isMax) next < current else next > current
                    if (isExtremum) {
                        extrema.add(i + (j - i) / 2)
                    }
                }
                i = j 
            }
            i++
        }
        return extrema
    }

    /**
     * Projects current temperature forward from the last observation using the hourly forecast
     * trend. Returns null when hourly data lacks coverage for either the observation time or now
     * (callers should fall back to raw observedTemp or pure interpolation).
     */
    fun deltaCorrectedTemp(
        observedTemp: Float,
        observedAt: Long,
        hourly: List<HourlyForecast>,
        nowMs: Long,
    ): Float? {
        val forecastAtObs = getInterpolatedTemperature(hourly, observedAt) ?: return null
        val forecastNow = getInterpolatedTemperature(hourly, nowMs) ?: return null
        return forecastNow + (observedTemp - forecastAtObs)
    }

    /**
     * Gets the next scheduled update time based on temperature difference.
     *
     * @param targetEpochMs The current time in epoch millis
     * @param tempDifference The difference between current and next hour temperatures
     * @return The next time the widget should update its temperature display in epoch millis
     */
    fun getNextUpdateTime(
        targetEpochMs: Long,
        tempDifference: Int,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Long {
        val updatesPerHour = getUpdatesPerHour(tempDifference)
        val intervalMinutes = 60 / updatesPerHour

        val currentTime = java.time.Instant.ofEpochMilli(targetEpochMs).atZone(zoneId).toLocalDateTime()
        val currentMinute = currentTime.minute
        val nextUpdateMinute = ((currentMinute / intervalMinutes) + 1) * intervalMinutes

        val nextUpdateTime = if (nextUpdateMinute >= 60) {
            currentTime.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        } else {
            currentTime.truncatedTo(ChronoUnit.HOURS).plusMinutes(nextUpdateMinute.toLong())
        }
        return nextUpdateTime.atZone(zoneId).toInstant().toEpochMilli()
    }
}
