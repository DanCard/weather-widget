package com.weatherwidget.widget

import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.model.WeatherSource

/**
 * Helper for resolving the most recent observed temperature from current temp records.
 */
object ObservationResolver {

    data class ObservedCurrentTemperature(
        val temperature: Float,
        val observedAt: Long,
        val source: String,
        val rowFetchedAt: Long,
    )

    /**
     * Finds the latest observation for the specified weather source from a list of current temp records.
     * Includes fallback to GENERIC_GAP source.
     */
    fun resolveObservedCurrentTemp(
        currentTemps: List<CurrentTempEntity>,
        displaySource: WeatherSource,
    ): ObservedCurrentTemperature? {
        return currentTemps
            .filter {
                it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id
            }
            .maxByOrNull { it.observedAt }
            ?.let { entity ->
                ObservedCurrentTemperature(
                    temperature = entity.temperature,
                    observedAt = entity.observedAt,
                    source = entity.source,
                    rowFetchedAt = entity.fetchedAt,
                )
            }
    }
}
