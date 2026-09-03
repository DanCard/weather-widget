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
    /** The header's window. Kept as the default so existing call sites read unchanged. */
    const val DEFAULT_LOOKAHEAD_HOURS = 8L
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
    ): Int? = maxPrecipProbabilityWithin(
        lookaheadHours = DEFAULT_LOOKAHEAD_HOURS,
        hourlyForecasts = hourlyForecasts,
        displaySourceId = displaySourceId,
        fallbackSourceId = fallbackSourceId,
        fallbackDailyProbability = fallbackDailyProbability,
        referenceTime = referenceTime,
    )

    /**
     * Maximum interpolated precipitation probability within [lookaheadHours] of [referenceTime].
     *
     * The window length is a parameter because two callers want different horizons over identical
     * machinery: the daily header asks for [DEFAULT_LOOKAHEAD_HOURS], while the today-column tap
     * gate ([DayClickResolver.routingPrecipProbability]) asks only about the span the graph it is
     * opening will actually show. Sharing the body keeps the number the header prints and the
     * number the tap obeys differing by window length alone, never by method.
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
        val sourceForecasts = hourlyForecasts.filter { (it.source == null || it.source == displaySourceId) && it.precipProbability != null }
        val candidateForecasts = sourceForecasts.ifEmpty {
            hourlyForecasts.filter { it.source == fallbackSourceId && it.precipProbability != null }
        }
        if (candidateForecasts.isEmpty()) return fallbackDailyProbability

        val zoneId = ZoneId.systemDefault()
        val selectedForecasts = candidateForecasts
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

        var maxInterpolatedProbability: Float? = null
        for (minuteOffset in 0 until lookaheadHours * MINUTES_PER_HOUR) {
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

        val windowStartHourMs = referenceTime.truncatedTo(ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val windowEndMs = referenceTime.plusHours(lookaheadHours).atZone(zoneId).toInstant().toEpochMilli()
        val exactPointFallback = selectedForecasts
            .filterKeys { it in windowStartHourMs until windowEndMs }
            .values
            .maxOrNull()

        return exactPointFallback ?: fallbackDailyProbability
    }

    /**
     * Returns true if more than half of the probability-weighted minutes in the next 8-hour window
     * fall after sunset / before sunrise, i.e. the rain is predominantly nighttime. Android's daily
     * header uses this to shrink the header rain chance by [DailyRainLabels.NIGHT_SCALE]; desktop
     * applies the same rule so both platforms size identically.
     *
     * @param sunriseHour Sunrise in fractional 24h (from SunPositionUtils.SunTimes)
     * @param sunsetHour  Sunset  in fractional 24h (from SunPositionUtils.SunTimes)
     */
    fun isNext8HourPrecipPredominantlyNight(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        fallbackSourceId: String,
        referenceTime: LocalDateTime,
        sunriseHour: Double,
        sunsetHour: Double,
    ): Boolean {
        if (sunsetHour >= 24.0) return false   // midnight sun — no nighttime
        if (sunriseHour <= 0.0) return true    // polar night — always night

        // Same source-selection convention as getNext8HourPrecipProbability.
        val sourceForecasts = hourlyForecasts.filter {
            (it.source == null || it.source == displaySourceId) && it.precipProbability != null
        }
        val candidateForecasts = sourceForecasts.ifEmpty {
            hourlyForecasts.filter { it.source == fallbackSourceId && it.precipProbability != null }
        }
        if (candidateForecasts.isEmpty()) return false

        val selectedForecasts = candidateForecasts
            .groupBy { it.dateTime }
            .mapValues { (_, items) -> items.maxOf { checkNotNull(it.precipProbability) } }

        var nightSum = 0f
        var daySum = 0f
        for (minuteOffset in 0 until DEFAULT_LOOKAHEAD_HOURS * MINUTES_PER_HOUR) {
            val sampleTime = referenceTime.plusMinutes(minuteOffset)
            val prob = interpolatePrecipProbabilityAt(selectedForecasts, sampleTime) ?: continue
            val hourOfDay = sampleTime.hour + sampleTime.minute / 60.0
            if (hourOfDay < sunriseHour || hourOfDay >= sunsetHour) nightSum += prob
            else daySum += prob
        }
        return nightSum > daySum
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
