package com.weatherwidget.widget

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.TemperatureInterpolator
import java.time.LocalDateTime
import java.time.ZoneId

data class CurrentTemperatureResolution(
    val displayTemp: Float?,
    val estimatedTemp: Float?,
    val observedTemp: Float?,
    val isStaleEstimate: Boolean,
)

/**
 * Resolves widget header temperature from two sources:
 * - estimated current temperature from hourly interpolation,
 * - observed/API current temperature fallback.
 */
object CurrentTemperatureResolver {
    private const val STALE_HOURLY_FETCH_THRESHOLD_MS = 2 * 60 * 60 * 1000L
    private val interpolator = TemperatureInterpolator()

    fun resolve(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        observedCurrentTemp: Float?,
    ): CurrentTemperatureResolution {
        val estimatedTemp =
            interpolator.getInterpolatedTemperature(
                hourlyForecasts = hourlyForecasts,
                targetTime = now,
                source = displaySource,
            )
        val isStaleEstimate = estimatedTemp != null && isStaleHourlyData(now, displaySource, hourlyForecasts)
        val displayTemp = estimatedTemp ?: observedCurrentTemp

        return CurrentTemperatureResolution(
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = observedCurrentTemp,
            isStaleEstimate = isStaleEstimate,
        )
    }

    fun formatDisplayTemperature(
        temp: Float,
        numColumns: Int,
        isStaleEstimate: Boolean,
    ): String {
        return when {
            isStaleEstimate -> String.format("%.0f°", temp)
            numColumns >= 2 -> String.format("%.1f°", temp)
            else -> String.format("%.0f°", temp)
        }
    }

    private fun isStaleHourlyData(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
    ): Boolean {
        if (hourlyForecasts.isEmpty()) return true

        val sourceScopedForecasts =
            hourlyForecasts.filter {
                it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id
            }
        if (sourceScopedForecasts.isEmpty()) return true

        val latestFetchMs = sourceScopedForecasts.maxOfOrNull { it.fetchedAt } ?: return true
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return (nowMs - latestFetchMs) > STALE_HOURLY_FETCH_THRESHOLD_MS
    }
}
