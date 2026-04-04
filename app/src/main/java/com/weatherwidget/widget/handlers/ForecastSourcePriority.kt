package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource

internal fun resolveForecastsByTime(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
): Map<Long, HourlyForecastEntity?> =
    hourlyForecasts.groupBy { it.dateTime }
        .mapValues { entry ->
            entry.value.find { it.source == displaySource.id }
                ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                ?: entry.value.firstOrNull()
        }
