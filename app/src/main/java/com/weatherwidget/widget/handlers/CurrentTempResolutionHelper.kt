package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime

internal object CurrentTempResolutionHelper {

    fun resolveAndPersistDelta(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        lat: Double,
        lon: Double,
        smoothedForecasts: Map<Long, Float>? = null,
    ): Pair<CurrentTemperatureResolution, Long> {
        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource),
                currentLat = lat,
                currentLon = lon,
                smoothedForecasts = smoothedForecasts,
            )
        val resolveMs = SystemClock.elapsedRealtime() - resolveStartMs
        if (currentTempResolution.shouldClearStoredDelta) {
            stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
        }
        currentTempResolution.updatedDeltaState?.let {
            stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it)
        }
        return currentTempResolution to resolveMs
    }
}
