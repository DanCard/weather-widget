package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import java.time.LocalDateTime

internal object CurrentTempResolutionHelper {

    /** [CurrentTemperatureResolver.resolve] and how long it took (the paint logs report it). */
    fun resolveTimed(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        smoothedForecasts: Map<Long, Float>? = null,
    ): Pair<CurrentTemperatureResolution, Long> {
        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                smoothedForecasts = smoothedForecasts,
            )
        return currentTempResolution to (SystemClock.elapsedRealtime() - resolveStartMs)
    }
}
