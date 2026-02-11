package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.widget.WidgetStateManager

/**
 * Abstract base interface for view handlers.
 * Each handler is responsible for rendering a specific view mode.
 */
interface WidgetViewHandler {
    /**
     * Update the widget with data for this view mode.
     *
     * @param context The context
     * @param appWidgetManager The AppWidgetManager instance
     * @param appWidgetId The widget ID
     * @param weatherList List of weather entities
     * @param forecastSnapshots Map of forecast snapshots by date
     * @param hourlyForecasts List of hourly forecasts
     */
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<WeatherEntity>,
        forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
    )

    /**
     * Check if this handler can handle the current view mode.
     *
     * @param stateManager The widget state manager
     * @param appWidgetId The widget ID
     * @return true if this handler should handle the current view mode
     */
    fun canHandle(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
    ): Boolean
}
