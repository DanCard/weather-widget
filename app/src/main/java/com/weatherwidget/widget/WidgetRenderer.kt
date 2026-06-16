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
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.widget.handlers.CurrentTempResolver
import com.weatherwidget.widget.handlers.GraphDataLoader
import com.weatherwidget.widget.handlers.CloudCoverViewHandler
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.ObservationData
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import com.weatherwidget.widget.handlers.WeatherData
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
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

    /**
     * Minimal fallback shown when a widget update throws unexpectedly. Without this, a crash mid-update
     * leaves the "Loading..." placeholder on the home screen indefinitely (see [updateWidgetLoading]).
     * Tapping the widget retriggers an update, so the "Tap to refresh" hint gives the user a recovery path.
     * Kept deliberately simple — it must never itself throw.
     */
    fun updateWidgetError(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setTextViewText(R.id.day2_label, "Today")
            views.setTextViewText(R.id.day2_high, "--°")
            views.setTextViewText(R.id.day2_low, "Tap to refresh")
            Log.d(TAG, "WIDGET_PAINT widget=$appWidgetId caller=error state=error thread=${Thread.currentThread().name}")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            Log.e(TAG, "updateWidgetError failed for widget=$appWidgetId", e)
        }
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
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val effectiveViewMode =
            if (dimensions.rows == 1 && viewMode != ViewMode.DAILY) {
                ViewMode.DAILY
            } else {
                viewMode
            }
        Log.d(
            TAG,
            "updateWidgetInternal: widget=$appWidgetId viewMode=$viewMode effectiveViewMode=$effectiveViewMode " +
                "rows=${dimensions.rows} zoom=${stateManager.getZoomLevel(appWidgetId)}",
        )

        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val now = LocalDateTime.now()
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        // History views render from a fixed anchor so this automatic refresh doesn't drift them forward;
        // only views that still include the now/fetch-dot point keep tracking live `now`.
        val centerTime = stateManager.resolveHourlyCenterTime(appWidgetId, now, zoom)

        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val locationLat =
            configuredLocation?.first
                ?: weatherList.firstOrNull()?.locationLat
                ?: hourlyForecasts.firstOrNull()?.locationLat
                ?: currentTemps.firstOrNull()?.locationLat
                ?: WeatherWidgetWorker.DEFAULT_LAT
        val locationLon =
            configuredLocation?.second
                ?: weatherList.firstOrNull()?.locationLon
                ?: hourlyForecasts.firstOrNull()?.locationLon
                ?: currentTemps.firstOrNull()?.locationLon
                ?: WeatherWidgetWorker.DEFAULT_LON

        // 1. Pick the single coordinate pair in the hourly data that is closest to our target location.
        // This avoids mixing data from multiple Mountain View markers (e.g. 37.422 vs 37.4168)
        // which would otherwise cause the smoothing and interpolation to jitter.
        val bestHourlyMatch = hourlyForecasts
            .asSequence()
            .map { it.locationLat to it.locationLon }
            .distinct()
            .minByOrNull { (lat, lon) -> Math.abs(lat - locationLat) + Math.abs(lon - locationLon) }

        val unifiedHourlyForecasts = if (bestHourlyMatch != null) {
            hourlyForecasts.filter { it.locationLat == bestHourlyMatch.first && it.locationLon == bestHourlyMatch.second }
        } else hourlyForecasts

        // Filter hourly forecasts to the NOW-centered window for current temp resolution.
        // This ensures the current temp display is always based on forecasts around NOW,
        // not any scrolled graph window.
        val nowResolutionWindow = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val nowZoneId = ZoneId.systemDefault()
        val nowMinEpoch = nowResolutionWindow.start.atZone(nowZoneId).toInstant().toEpochMilli()
        val nowMaxEpoch = nowResolutionWindow.end.atZone(nowZoneId).toInstant().toEpochMilli()
        val nowCenteredHourlyForecasts = unifiedHourlyForecasts.filter { row ->
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
                val resolved = ActualsAggregator.resolveCurrentObservation(
                    observations = currentTemps.map { it.toReading() },
                    hourlyForecasts = nowCenteredHourlyForecasts.map { it.toHourlyForecast() },
                    displaySourceId = displaySource.id,
                    userLat = locationLat,
                    userLon = locationLon,
                    nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    lookbackHours = 12L,
                    lookaheadHours = 2L,
                )
                resolved?.let { (temp, timestamp, fetchedAt) ->
                    ObservationResolver.ObservedCurrentTemperature(
                        temperature = temp,
                        observedAt = timestamp,
                        source = displaySource.id,
                        rowFetchedAt = fetchedAt
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

        when (effectiveViewMode) {
            ViewMode.TEMPERATURE -> {
                TemperatureViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = unifiedHourlyForecasts,
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
                    hourlyForecasts = unifiedHourlyForecasts,
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
                    hourlyForecasts = unifiedHourlyForecasts,
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
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    weatherData = WeatherData(
                        weatherList = weatherList,
                        forecastSnapshots = forecastSnapshots,
                        hourlyForecasts = unifiedHourlyForecasts,
                        currentTemps = currentTemps,
                        dailyActualsBySource = dailyActualsBySource,
                    ),
                    observationData = ObservationData(
                        lastObservedTemp = observation?.temperature,
                        observedAt = observation?.observedAt,
                        currentTempHourlyForecasts = nowCenteredHourlyForecasts,
                    ),
                    now = LocalDateTime.now(),
                    startupToken = startupToken,
                    stateManagerNullable = stateManager,
                    repository = repository,
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
                "view" to effectiveViewMode,
                "storedView" to viewMode,
                "hourlyCount" to unifiedHourlyForecasts.size,
                "forecastCount" to weatherList.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }
}
