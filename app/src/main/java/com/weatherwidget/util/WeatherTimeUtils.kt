package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import java.time.ZoneId

object WeatherTimeUtils {
    const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

    fun alignToNearestHourHalfUp(dateTime: LocalDateTime): LocalDateTime =
        com.weatherwidget.shared.util.WeatherTimeUtils.alignToNearestHourHalfUp(dateTime)

    fun toHourlyForecastKeyMs(dateTime: LocalDateTime): Long {
        return alignToNearestHourHalfUp(dateTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun getCurrentHourForecast(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): HourlyForecastEntity? {
        val currentHourKey = toHourlyForecastKeyMs(LocalDateTime.now())
        return hourlyForecasts
            .filter { it.dateTime == currentHourKey }
            .let { forecasts ->
                forecasts.find { it.source == displaySource.id }
                    ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: forecasts.firstOrNull()
            }
    }
}