package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime

fun CurrentTemperatureResolver.resolve(
    now: LocalDateTime,
    displaySource: WeatherSource,
    hourlyForecasts: List<HourlyForecastEntity>,
    lastObservedTemp: Float?,
    observedAt: Long?,
    smoothedForecasts: Map<Long, Float>? = null,
): CurrentTemperatureResolution {
    return resolve(
        now = now,
        displaySource = displaySource,
        hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
        lastObservedTemp = lastObservedTemp,
        observedAt = observedAt,
        smoothedForecasts = smoothedForecasts,
    )
}

fun CurrentTemperatureResolver.resolveQuick(
    now: LocalDateTime,
    displaySource: WeatherSource,
    hourlyForecasts: List<HourlyForecastEntity>,
    lastObservedTemp: Float?,
    smoothedForecasts: Map<Long, Float>? = null,
): QuickCurrentTemperature {
    return resolveQuick(
        now = now,
        displaySource = displaySource,
        hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
        lastObservedTemp = lastObservedTemp,
        smoothedForecasts = smoothedForecasts,
    )
}
