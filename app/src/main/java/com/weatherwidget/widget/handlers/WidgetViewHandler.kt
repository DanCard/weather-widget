package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime

data class WeatherData(
    val weatherList: List<ForecastEntity>,
    val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
    val hourlyForecasts: List<HourlyForecastEntity>,
    val currentTemps: List<ObservationEntity> = emptyList(),
    val dailyActualsBySource: DailyActualsBySource = emptyMap(),
)

data class ObservationData(
    val lastObservedTemp: Float? = null,
    val observedAt: Long? = null,
    val smoothedForecasts: Map<Long, Float>? = null,
    val currentTempHourlyForecasts: List<HourlyForecastEntity> = emptyList(),
)

/**
 * Abstract base interface for view handlers.
 * Each handler is responsible for rendering a specific view mode.
 */
interface WidgetViewHandler {
    /**
     * Update the widget with data for this view mode.
     */
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherData: WeatherData,
        observationData: ObservationData,
        now: LocalDateTime = LocalDateTime.now(),
        startupToken: String? = null,
        stateManagerNullable: WidgetStateManager? = null,
        repository: WeatherRepository? = null,
        // True for background (worker-driven) repaints: push via partiallyUpdateAppWidget so the
        // launcher patches the existing view tree in place instead of tearing it down and
        // re-inflating — the re-inflate is a visible flash on Samsung's launcher. Safe because
        // binders set every view on every paint (sticky-visibility discipline). Full pushes are
        // reserved for paths that must (re)establish the hierarchy: onUpdate, resize, interaction.
        partialPush: Boolean = false,
    )

    /**
     * Check if this handler can handle the current view mode.
     */
    fun canHandle(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
    ): Boolean
}
