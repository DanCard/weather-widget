package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.ObservationBlender
import com.weatherwidget.widget.handlers.CloudCoverViewHandler
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import java.time.LocalDate
import java.time.LocalDateTime

object WidgetRenderer {

    private const val TAG = "WidgetRenderer"

    fun updateWidgetLoading(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        views.setViewVisibility(R.id.text_container, View.VISIBLE)
        views.setViewVisibility(R.id.graph_view, View.GONE)
        views.setTextViewText(R.id.day2_label, "Today")
        views.setTextViewText(R.id.day2_high, "--°")
        views.setTextViewText(R.id.day2_low, "Loading...")
        Log.d(TAG, "WIDGET_PAINT widget=$appWidgetId caller=loading state=loading thread=${Thread.currentThread().name}")
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    suspend fun updateWidgetWithData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>> = emptyMap(),
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
        currentTemps: List<ObservationEntity> = emptyList(),
        dailyActualsBySource: DailyActualsBySource = emptyMap(),
        repository: WeatherRepository? = null,
        startupToken: String? = null,
    ) {
        val renderStartMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "updateWidgetInternal: widget=$appWidgetId viewMode=$viewMode zoom=${stateManager.getZoomLevel(appWidgetId)}")

        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val now = LocalDateTime.now()
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val centerTime = now.plusHours(hourlyOffset.toLong())

        val graphStyleObs = ObservationBlender.resolveCurrentObservation(
            observations = currentTemps,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            userLat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT,
            userLon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON,
            now = now,
            lookbackHours = 12L,
            lookaheadHours = 2L
        )
        val observation = graphStyleObs ?: ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)?.let { Triple(it.temperature, it.observedAt, it.observedAt) }

        val targetDateEpoch = centerTime.toLocalDate().toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val targetPrecip = weatherList
            .find { it.targetDate == targetDateEpoch && it.source == displaySource.id }
            ?.precipProbability

        when (viewMode) {
            ViewMode.TEMPERATURE -> {
                TemperatureViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.first,
                    observedAt = observation?.second,
                    repository = repository,
                    startupToken = startupToken,
                    deferCurrentTempResolution = startupToken != null,
                )
            }
            ViewMode.PRECIPITATION -> {
                PrecipViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.first,
                    observedAt = observation?.second,
                    repository = repository,
                    startupToken = startupToken,
                )
            }
            ViewMode.CLOUD_COVER -> {
                CloudCoverViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.first,
                    observedAt = observation?.second,
                    repository = repository,
                    startupToken = startupToken,
                )
            }
            ViewMode.DAILY -> {
                DailyViewHandler.updateWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    weatherList,
                    forecastSnapshots,
                    hourlyForecasts,
                    currentTemps,
                    dailyActualsBySource,
                    repository,
                    startupToken = startupToken,
                )
            }
        }

        val totalMs = SystemClock.elapsedRealtime() - renderStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
            thresholdMs = WidgetPerfLogger.WIDGET_RENDER_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_WIDGET_RENDER_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to viewMode,
                "hourlyCount" to hourlyForecasts.size,
                "forecastCount" to weatherList.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }
}
