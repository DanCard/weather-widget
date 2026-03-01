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

        val nowHour = LocalDateTime.parse(WeatherTimeUtils.toHourlyForecastKey(referenceTime))
        val windowEndExclusive = nowHour.plusHours(LOOKAHEAD_HOURS)
        val next8HourValues =
            candidateForecasts
                .asSequence()
                .mapNotNull { forecast ->
                    try {
                        LocalDateTime.parse(forecast.dateTime) to forecast.precipProbability
                    } catch (e: Exception) {
                        null
                    }
                }
                .filter { (forecastHour, _) ->
                    !forecastHour.isBefore(nowHour) && forecastHour.isBefore(windowEndExclusive)
                }
                .mapNotNull { (_, precipProbability) -> precipProbability }
                .toList()

        if (next8HourValues.isNotEmpty()) {
            return next8HourValues.maxOrNull() ?: 0
        }

        return fallbackDailyProbability
    }

    fun getPrecipTextSize(precipProb: Int): Float {
        return when {
            precipProb <= 8 -> 26f * 0.7f
            precipProb <= 15 -> 26f * 0.8f
            precipProb <= 25 -> 26f * 0.9f
            else -> 26f
        }
    }
}
