package com.weatherwidget.widget

import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource

/**
 * Helper for resolving the most recent observed temperature from weather records.
 */
object ObservationResolver {

    data class ObservedCurrentTemperature(
        val temperature: Float,
        val observedAt: Long,
        val source: String,
        val rowFetchedAt: Long,
    )

    /**
     * Finds the latest observation for the specified weather source from a list of weather records.
     * Includes fallback to GENERIC_GAP source.
     */
    fun resolveObservedCurrentTemp(
        weatherList: List<WeatherEntity>,
        displaySource: WeatherSource,
        todayStr: String,
    ): ObservedCurrentTemperature? {
        // Find the latest observation specifically for the ACTIVE source (or gap source).
        return weatherList
            .filter {
                it.date == todayStr &&
                    it.currentTemp != null &&
                    (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
            }
            .maxByOrNull { it.currentTempObservedAt ?: it.fetchedAt }
            ?.let { weather ->
                val currentTemp = weather.currentTemp ?: return@let null
                ObservedCurrentTemperature(
                    temperature = currentTemp,
                    observedAt = weather.currentTempObservedAt ?: weather.fetchedAt,
                    source = weather.source,
                    rowFetchedAt = weather.fetchedAt,
                )
            }
    }
}
