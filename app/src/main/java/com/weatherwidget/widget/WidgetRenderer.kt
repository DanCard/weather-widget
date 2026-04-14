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
import com.weatherwidget.widget.handlers.CurrentTempResolver
import com.weatherwidget.widget.handlers.GraphDataLoader
import com.weatherwidget.widget.handlers.CloudCoverViewHandler
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import java.time.ZoneId
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

        val locationLat =
            weatherList.firstOrNull()?.locationLat
                ?: hourlyForecasts.firstOrNull()?.locationLat
                ?: currentTemps.firstOrNull()?.locationLat
                ?: WeatherWidgetWorker.DEFAULT_LAT
        val locationLon =
            weatherList.firstOrNull()?.locationLon
                ?: hourlyForecasts.firstOrNull()?.locationLon
                ?: currentTemps.firstOrNull()?.locationLon
                ?: WeatherWidgetWorker.DEFAULT_LON

        // Filter hourly forecasts to the NOW-centered window for current temp resolution.
        // This ensures the current temp display is always based on forecasts around NOW,
        // not any scrolled graph window.
        val nowResolutionWindow = GraphDataLoader.buildCurrentTempResolutionWindow(now)
        val nowZoneId = ZoneId.systemDefault()
        val nowMinEpoch = nowResolutionWindow.start.atZone(nowZoneId).toInstant().toEpochMilli()
        val nowMaxEpoch = nowResolutionWindow.end.atZone(nowZoneId).toInstant().toEpochMilli()
        val nowCenteredHourlyForecasts = hourlyForecasts.filter { row ->
            row.locationLat == locationLat &&
                row.locationLon == locationLon &&
                row.dateTime in nowMinEpoch..nowMaxEpoch
        }

        val graphStyleObs =
            if (repository != null) {
                val observations = repository.getObservationsInRange(nowMinEpoch, nowMaxEpoch, locationLat, locationLon)
                CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
                    observations = observations,
                    hourlyForecasts = nowCenteredHourlyForecasts,
                    displaySource = displaySource,
                    lat = locationLat,
                    lon = locationLon,
                    now = now,
                    queryWindow = nowResolutionWindow,
                )
            } else {
                ObservationBlender.resolveCurrentObservation(
                    observations = currentTemps,
                    hourlyForecasts = hourlyForecasts,
                    displaySource = displaySource,
                    userLat = locationLat,
                    userLon = locationLon,
                    now = now,
                    lookbackHours = 12L,
                    lookaheadHours = 2L,
                )?.let { (temp, observedAt, anchorAt) ->
                    ObservationResolver.ObservedCurrentTemperature(
                        temperature = temp,
                        observedAt = anchorAt,
                        source = displaySource.id,
                        rowFetchedAt = observedAt,
                    )
                }
            }
        val fallbackObservation = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
        val observation = graphStyleObs ?: fallbackObservation
        val observationSource =
            when {
                graphStyleObs != null -> "graph_style"
                observation != null -> "raw_observation"
                else -> "none"
            }
        Log.d(
            TAG,
            "currentObservationSelection: widget=$appWidgetId viewMode=$viewMode zoom=$zoom " +
                "source=${displaySource.id} selected=$observationSource " +
                "graphStyleTemp=${graphStyleObs?.temperature} graphStyleObservedAt=${graphStyleObs?.observedAt} graphStyleRowFetchedAt=${graphStyleObs?.rowFetchedAt} " +
                "fallbackTemp=${fallbackObservation?.temperature} fallbackObservedAt=${fallbackObservation?.observedAt} " +
                "finalTemp=${observation?.temperature} finalObservedAt=${observation?.observedAt}",
        )

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
                    currentTempHourlyForecasts = nowCenteredHourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = targetPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
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
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
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
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
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
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
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
