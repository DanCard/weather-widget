package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object WeatherTimeUtils {
    const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

    fun alignToNearestHourHalfUp(dateTime: LocalDateTime): LocalDateTime {
        val truncatedHour = dateTime.truncatedTo(ChronoUnit.HOURS)
        return if (dateTime.minute >= 30) truncatedHour.plusHours(1) else truncatedHour
    }

    fun toHourlyForecastKeyMs(dateTime: LocalDateTime): Long {
        return alignToNearestHourHalfUp(dateTime)
            .atZone(java.time.ZoneId.systemDefault())
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
