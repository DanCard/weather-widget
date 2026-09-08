package com.weatherwidget.widget

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity

/**
 * Resolves the target geographic coordinate for rendering a widget.
 * Extracted from [WidgetRenderer].
 */
object WidgetRenderLocationResolver {

    fun resolve(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<ObservationEntity>,
    ): Pair<Double, Double>? {
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val lat = configuredLocation?.first
            ?: weatherList.firstOrNull()?.locationLat
            ?: hourlyForecasts.firstOrNull()?.locationLat
            ?: currentTemps.firstOrNull()?.locationLat
        val lon = configuredLocation?.second
            ?: weatherList.firstOrNull()?.locationLon
            ?: hourlyForecasts.firstOrNull()?.locationLon
            ?: currentTemps.firstOrNull()?.locationLon

        return if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
            lat to lon
        } else {
            null
        }
    }
}
