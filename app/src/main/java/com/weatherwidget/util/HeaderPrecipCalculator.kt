package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HeaderPrecipCalculator {
    fun getForwardLookingTodayPrecipProbability(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        now: LocalDateTime,
    ): Int? {
        val sourceForecasts = hourlyForecasts.filter { it.source == displaySource.id }
        val candidateForecasts =
            if (sourceForecasts.isNotEmpty()) {
                sourceForecasts
            } else {
                hourlyForecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }
            }

        val todayDate = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowHourKey = WeatherTimeUtils.toHourlyForecastKey(now)
        val forwardLookingValues =
            candidateForecasts
                .asSequence()
                .filter { it.dateTime.startsWith(todayDate) && it.dateTime >= nowHourKey }
                .mapNotNull { it.precipProbability }
                .toList()

        if (forwardLookingValues.isNotEmpty()) {
            val maxForwardLooking = forwardLookingValues.maxOrNull() ?: 0
            return maxForwardLooking.takeIf { it > 0 }
        }

        return fallbackDailyProbability?.takeIf { it > 0 }
    }
}
