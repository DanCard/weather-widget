package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Minute-level interpolation of precipitation probability for header display.
 * Pure-Kotlin, parameterized on shared [HourlyForecast] model.
 */
object PrecipProbabilityCalculator {
    private const val LOOKAHEAD_HOURS = 8L
    private const val MINUTES_PER_HOUR = 60L

    /**
     * Computes the maximum interpolated precipitation probability within the next 8 hours.
     *
     * @param hourlyForecasts All available hourly forecasts
     * @param displaySourceId The primary weather source ID to use
     * @param fallbackSourceId Secondary source ID to try when primary has no data (e.g. "Generic")
     * @param fallbackDailyProbability Fallback from daily forecast if no hourly data
     * @param referenceTime Current time
     * @return Maximum probability (0-100), or fallbackDailyProbability if no data
     */
    fun getNext8HourPrecipProbability(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        fallbackSourceId: String,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? {
        val sourceForecasts = hourlyForecasts.filter { it.source == displaySourceId && it.precipProbability != null }
        val candidateForecasts = sourceForecasts.ifEmpty {
            hourlyForecasts.filter { it.source == fallbackSourceId && it.precipProbability != null }
        }
        if (candidateForecasts.isEmpty()) return fallbackDailyProbability

        val selectedForecasts = candidateForecasts
            .groupBy { it.dateTime }
            .mapValues { (_, items) -> items.maxOf { checkNotNull(it.precipProbability) } }

        var maxInterpolatedProbability: Float? = null
        for (minuteOffset in 0 until LOOKAHEAD_HOURS * MINUTES_PER_HOUR) {
            val sampleTime = referenceTime.plusMinutes(minuteOffset)
            val sampleProbability = interpolatePrecipProbabilityAt(selectedForecasts, sampleTime)
            if (sampleProbability != null) {
                maxInterpolatedProbability = if (maxInterpolatedProbability == null) {
                    sampleProbability
                } else {
                    maxOf(maxInterpolatedProbability, sampleProbability)
                }
            }
        }

        if (maxInterpolatedProbability != null) {
            return maxInterpolatedProbability.roundToInt()
        }

        val zoneId = ZoneId.systemDefault()
        val windowStartMs = referenceTime.atZone(zoneId).toInstant().toEpochMilli()
        val windowEndMs = referenceTime.plusHours(LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val exactPointFallback = selectedForecasts
            .filterKeys { it in windowStartMs until windowEndMs }
            .values
            .maxOrNull()

        return exactPointFallback ?: fallbackDailyProbability
    }

    private fun interpolatePrecipProbabilityAt(
        forecastsByHour: Map<Long, Int>,
        targetTime: LocalDateTime,
    ): Float? {
        if (forecastsByHour.isEmpty()) return null

        val zoneId = ZoneId.systemDefault()
        val currentHour = targetTime.truncatedTo(ChronoUnit.HOURS)
        val nextHour = currentHour.plusHours(1)
        val currentHourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
        val nextHourMs = nextHour.atZone(zoneId).toInstant().toEpochMilli()

        val currentProbability = forecastsByHour[currentHourMs]?.toFloat()
        val nextProbability = forecastsByHour[nextHourMs]?.toFloat()

        return when {
            currentProbability != null && nextProbability != null -> {
                val factor = targetTime.minute / 60.0f
                currentProbability + ((nextProbability - currentProbability) * factor)
            }
            currentProbability != null && targetTime.minute == 0 -> currentProbability
            else -> null
        }
    }
}
