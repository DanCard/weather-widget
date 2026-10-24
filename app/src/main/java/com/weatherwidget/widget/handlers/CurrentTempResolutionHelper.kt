package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.CurrentTempRun
import com.weatherwidget.widget.CurrentTemperatureResolution
import java.time.LocalDateTime

internal object CurrentTempResolutionHelper {

    /**
     * `:shared` [CurrentTempRun.header] on Android rows. [hourlyForecasts] may be wider than the
     * resolution window; the run cuts it around [now].
     */
    fun header(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
    ): CurrentTemperatureResolution =
        CurrentTempRun.header(
            hourly = hourlyForecasts.map { it.toHourlyForecast() },
            displaySource = displaySource,
            nowLocal = now,
            observedTemp = lastObservedTemp,
            observedAt = observedAt,
        )

    /** [header] and how long it took (the paint logs report it). */
    fun resolveTimed(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
    ): Pair<CurrentTemperatureResolution, Long> {
        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution = header(now, displaySource, hourlyForecasts, lastObservedTemp, observedAt)
        return currentTempResolution to (SystemClock.elapsedRealtime() - resolveStartMs)
    }
}
