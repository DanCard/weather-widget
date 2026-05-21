package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime

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
     * @param currentTemps Current temperature observations (from _MAIN observation entries)
     */
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<ObservationEntity> = emptyList(),
        dailyActualsBySource: DailyActualsBySource = emptyMap(),
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        now: LocalDateTime = LocalDateTime.now(),
        startupToken: String? = null,
        smoothedForecasts: Map<Long, Float>? = null,
        stateManagerNullable: WidgetStateManager? = null,
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
