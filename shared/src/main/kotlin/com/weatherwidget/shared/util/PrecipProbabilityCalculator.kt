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
    /** Shared visible precipitation horizon, matching ZoomStage.WIDE's forward span. */
    const val VISIBLE_LOOKAHEAD_HOURS = 6L
    private const val MINUTES_PER_HOUR = 60L

    /**
     * Computes the maximum interpolated precipitation probability within the next 6 hours.
     *
     * @param hourlyForecasts All available hourly forecasts
     * @param displaySourceId The primary weather source ID to use
     * @param fallbackSourceId Secondary source ID to try when primary has no data (e.g. "Generic")
     * @param fallbackDailyProbability Fallback from daily forecast if no hourly data
     * @param referenceTime Current time
     * @return Maximum probability (0-100), or fallbackDailyProbability if no data
     */
    fun getNext6HourPrecipProbability(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        fallbackSourceId: String,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? = maxPrecipProbabilityWithin(
        lookaheadHours = VISIBLE_LOOKAHEAD_HOURS,
        hourlyForecasts = hourlyForecasts,
        displaySourceId = displaySourceId,
        fallbackSourceId = fallbackSourceId,
        fallbackDailyProbability = fallbackDailyProbability,
        referenceTime = referenceTime,
    )

    /**
     * Maximum interpolated precipitation probability within [lookaheadHours] of [referenceTime].
     *
     * The window length remains a parameter for callers that need a different horizon. The daily
     * header and today's tap gate both use [VISIBLE_LOOKAHEAD_HOURS], matching the forward span of
     * the graph the tap opens.
     *
     * @return Maximum probability (0-100), or [fallbackDailyProbability] if no hourly data applies
     */
    fun maxPrecipProbabilityWithin(
        lookaheadHours: Long,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        fallbackSourceId: String,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? {
        val zoneId = ZoneId.systemDefault()
        val selectedForecasts = selectPrecipitationSeries(hourlyForecasts, displaySourceId, fallbackSourceId, zoneId)
        return maxPrecipProbabilityWithin(selectedForecasts, lookaheadHours, referenceTime, zoneId)
            ?: fallbackDailyProbability
    }

    private fun selectPrecipitationSeries(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        fallbackSourceId: String,
        zoneId: ZoneId,
    ): Map<Long, Int> {
        val sourceForecasts = hourlyForecasts.filter {
            (it.source == null || it.source == displaySourceId) && it.precipProbability != null
        }
        val candidateForecasts = sourceForecasts.ifEmpty {
            hourlyForecasts.filter { it.source == fallbackSourceId && it.precipProbability != null }
        }
        return candidateForecasts
            .groupBy {
                Instant.ofEpochMilli(it.dateTime)
                    .atZone(zoneId)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.HOURS)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            }
            .mapValues { (_, items) -> items.maxOf { checkNotNull(it.precipProbability) } }
    }

    private fun maxPrecipProbabilityWithin(
        selectedForecasts: Map<Long, Int>,
        lookaheadHours: Long,
        referenceTime: LocalDateTime,
        zoneId: ZoneId,
    ): Int? {
        var maxInterpolatedProbability: Float? = null
        for (minuteOffset in 0 until lookaheadHours * MINUTES_PER_HOUR) {
            val sampleTime = referenceTime.plusMinutes(minuteOffset)
            val sampleProbability = interpolatePrecipProbabilityAt(selectedForecasts, sampleTime)
            if (sampleProbability != null) {
                maxInterpolatedProbability = maxInterpolatedProbability?.let {
                    maxOf(it, sampleProbability)
                } ?: sampleProbability
            }
        }
        if (maxInterpolatedProbability != null) return maxInterpolatedProbability.roundToInt()

        val windowStartHourMs = referenceTime.truncatedTo(ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val windowEndMs = referenceTime.plusHours(lookaheadHours).atZone(zoneId).toInstant().toEpochMilli()
        return selectedForecasts
            .filterKeys { it in windowStartHourMs until windowEndMs }
            .values
            .maxOrNull()
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
