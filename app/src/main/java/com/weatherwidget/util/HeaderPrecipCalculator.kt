package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime

object HeaderPrecipCalculator {
    private const val LOOKAHEAD_HOURS = 8L

    fun getNext8HourPrecipProbability(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? {
        val sourceForecasts = hourlyForecasts.filter { it.source == displaySource.id }
        val candidateForecasts =
            if (sourceForecasts.isNotEmpty()) {
                sourceForecasts
            } else {
                hourlyForecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }
            }

        val nowHourMs = WeatherTimeUtils.toHourlyForecastKeyMs(referenceTime)
        val windowEndMs = nowHourMs + LOOKAHEAD_HOURS * 3600 * 1000L
        val next8HourValues =
            candidateForecasts
                .filter { it.dateTime in nowHourMs until windowEndMs }
                .mapNotNull { it.precipProbability }

        if (next8HourValues.isNotEmpty()) {
            return next8HourValues.maxOrNull() ?: 0
        }

        return fallbackDailyProbability
    }

    fun getPrecipTextSize(precipProb: Int): Float {
        return when {
            precipProb <= 1 -> 26f * 0.4f
            precipProb <= 2 -> 26f * 0.5f
            precipProb <= 4 -> 26f * 0.6f
            precipProb <= 8 -> 26f * 0.7f
            precipProb <= 15 -> 26f * 0.8f
            precipProb <= 25 -> 26f * 0.9f
            else -> 26f
        }
    }
}
