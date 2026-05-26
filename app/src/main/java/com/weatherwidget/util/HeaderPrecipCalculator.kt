package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.handlers.HeaderConstants
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

object HeaderPrecipCalculator {
    private const val LOOKAHEAD_HOURS = 8L
    private const val MINUTES_PER_HOUR = 60L

    fun getNext8HourPrecipProbability(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? {
        val sourceForecasts = hourlyForecasts.filter { it.source == displaySource.id && it.precipProbability != null }
        val candidateForecasts =
            if (sourceForecasts.isNotEmpty()) {
                sourceForecasts
            } else {
                hourlyForecasts.filter { it.source == WeatherSource.GENERIC_GAP.id && it.precipProbability != null }
            }
        if (candidateForecasts.isEmpty()) return fallbackDailyProbability

        val selectedForecasts =
            candidateForecasts
                .groupBy { it.dateTime }
                .mapValues { (_, items) -> items.maxOf { checkNotNull(it.precipProbability) } }

        var maxInterpolatedProbability: Float? = null
        for (minuteOffset in 0 until LOOKAHEAD_HOURS * MINUTES_PER_HOUR) {
            val sampleTime = referenceTime.plusMinutes(minuteOffset)
            val sampleProbability = interpolatePrecipProbabilityAt(selectedForecasts, sampleTime)
            if (sampleProbability != null) {
                maxInterpolatedProbability =
                    if (maxInterpolatedProbability == null) {
                        sampleProbability
                    } else {
                        maxOf(maxInterpolatedProbability, sampleProbability)
                }
            }
        }

        if (maxInterpolatedProbability != null) {
            return maxInterpolatedProbability.roundToInt()
        }

        val windowStartMs = referenceTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val windowEndMs = referenceTime.plusHours(LOOKAHEAD_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exactPointFallback =
            selectedForecasts
                .filterKeys { it in windowStartMs until windowEndMs }
                .values
                .maxOrNull()

        return exactPointFallback ?: fallbackDailyProbability
    }

    const val NIGHT_SCALE = 0.72f

    fun getPrecipScaleFactor(precipProb: Int): Float = when {
        precipProb <= 1  -> 0.3f
        precipProb <= 2  -> 0.4f
        precipProb <= 4  -> 0.5f
        precipProb <= 8  -> 0.6f
        precipProb <= 16 -> 0.7f
        precipProb <= 32 -> 0.8f
        precipProb <= 64 -> 0.9f
        else             -> 1.0f
    }

    fun getPrecipTextSize(precipProb: Int): Float {
        return HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * getPrecipScaleFactor(precipProb)
    }

    /**
     * Returns true if more than half of the probability-weighted minutes in the next 8-hour window
     * fall after sunset / before sunrise, i.e. the rain is predominantly nighttime.
     *
     * @param sunriseHour Sunrise in fractional 24h (from SunPositionUtils.SunTimes)
     * @param sunsetHour  Sunset  in fractional 24h (from SunPositionUtils.SunTimes)
     */
    fun isNext8HourPrecipPredominantlyNight(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        referenceTime: LocalDateTime,
        sunriseHour: Double,
        sunsetHour: Double,
    ): Boolean {
        if (sunsetHour >= 24.0) return false   // midnight sun — no nighttime
        if (sunriseHour <= 0.0) return true    // polar night — always night

        val sourceForecasts = hourlyForecasts.filter { it.source == displaySource.id && it.precipProbability != null }
        val candidateForecasts =
            if (sourceForecasts.isNotEmpty()) {
                sourceForecasts
            } else {
                hourlyForecasts.filter { it.source == WeatherSource.GENERIC_GAP.id && it.precipProbability != null }
            }
        if (candidateForecasts.isEmpty()) return false

        val selectedForecasts =
            candidateForecasts
                .groupBy { it.dateTime }
                .mapValues { (_, items) -> items.maxOf { checkNotNull(it.precipProbability) } }

        var nightSum = 0f
        var daySum   = 0f
        for (minuteOffset in 0 until LOOKAHEAD_HOURS * MINUTES_PER_HOUR) {
            val sampleTime = referenceTime.plusMinutes(minuteOffset)
            val prob = interpolatePrecipProbabilityAt(selectedForecasts, sampleTime) ?: continue
            val hourOfDay = sampleTime.hour + sampleTime.minute / 60.0
            if (hourOfDay < sunriseHour || hourOfDay >= sunsetHour) nightSum += prob
            else                                                      daySum   += prob
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
