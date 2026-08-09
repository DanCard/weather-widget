package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetPushDispatcher
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.time.ZoneId

/** Owns graph navigation, zoom transitions, hourly data assembly, and view-specific dispatch. */
internal object GraphInteractionRenderer {
    private const val TAG = "GraphInteraction"

    data class GraphRenderRequest(
        val context: Context,
        val appWidgetId: Int,
        val refreshContext: WidgetRefreshContextResolver.Resolved,
        val now: LocalDateTime,
        val repository: WeatherRepository? = null,
        val startTimeMs: Long = SystemClock.elapsedRealtime(),
        val actionTag: String = "GRAPH_REFRESH",
        val extraMetadata: String = "",
        val partialPush: Boolean = false,
        val origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    )

    suspend fun navigate(request: GraphRenderRequest, isLeft: Boolean) {
        val stateManager = WidgetStateManager(request.context)
        val newOffset =
            if (isLeft) stateManager.navigateHourlyLeft(request.appWidgetId)
            else stateManager.navigateHourlyRight(request.appWidgetId)
        val direction = if (isLeft) "LEFT" else "RIGHT"
        Log.d(
            TAG,
            "navigate: offset=$newOffset direction=$direction widget=${request.appWidgetId}",
        )
        render(request.copy(actionTag = "GRAPH_NAV", extraMetadata = "dir=$direction"))
    }

    suspend fun cycleZoom(
        request: GraphRenderRequest,
        zoomCenterOffset: Int?,
    ) {
        val stateManager = WidgetStateManager(request.context)
        val viewMode = stateManager.getViewMode(request.appWidgetId)
        if (viewMode == ViewMode.DAILY) {
            Log.w(TAG, "cycleZoom: ignoring stale PendingIntent for widget ${request.appWidgetId}")
            return
        }
        val oldZoom = stateManager.getZoomStage(request.appWidgetId)
        val newZoom = stateManager.cycleZoomLevel(request.appWidgetId)
        if (zoomCenterOffset != null) {
            stateManager.setHourlyOffset(request.appWidgetId, zoomCenterOffset)
        }
        Log.d(
            TAG,
            "cycleZoom: $oldZoom -> $newZoom centerOffset=$zoomCenterOffset " +
                "widget=${request.appWidgetId}",
        )
        render(request.copy(actionTag = "CYCLE_ZOOM", extraMetadata = "zoom=${newZoom.name}"))
    }

    suspend fun selectedSourceNeedsRefresh(
        context: Context,
        appWidgetId: Int,
        refreshContext: WidgetRefreshContextResolver.Resolved,
        source: WeatherSource,
        now: LocalDateTime,
        nowMs: Long,
    ): Boolean {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val zoom = if (viewMode.isGraphMode) stateManager.getZoomWindow(appWidgetId) else null
        val centerTime = zoom?.let { stateManager.resolveHourlyCenterTime(appWidgetId, now, it) }
        val location = refreshContext.location
        val state =
            SourceStalenessProbe.sourceWindowState(
                forecastDao = refreshContext.forecastDao,
                hourlyDao = refreshContext.database.hourlyForecastDao(),
                hourlyHistoryDao = refreshContext.database.hourlyForecastHistoryDao(),
                lat = location.lat,
                lon = location.lon,
                source = source,
                centerTime = centerTime,
                zoom = zoom,
                now = now,
                lastSuccessfulFetchAtMs =
                    FetchMetadata.getLastForecastSourceSuccessTime(
                        context,
                        source.id,
                        location.lat,
                        location.lon,
                    ).takeIf { it > 0L },
            )
        return SourceStalenessProbe.sourceNeedsRefresh(state, nowMs)
    }

    suspend fun render(request: GraphRenderRequest) {
        val context = request.context
        val database = request.refreshContext.database
        val location = request.refreshContext.location
        val stateManager = WidgetStateManager(context)
        val zoom = stateManager.getZoomWindow(request.appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(request.appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(request.appWidgetId)
        val centerTime =
            stateManager.resolveHourlyCenterTime(request.appWidgetId, request.now, zoom)
        Log.d(
            TAG,
            "render: widget=${request.appWidgetId} view=${stateManager.getViewMode(request.appWidgetId)} " +
                "zoom=$zoom offset=$hourlyOffset now=${request.now} center=$centerTime " +
                "source=${displaySource.id}",
        )
        val hourlyForecasts =
            GraphDataLoader.loadGraphWindowHourlyForecasts(
                hourlyDao = database.hourlyForecastDao(),
                hourlyHistoryDao = database.hourlyForecastHistoryDao(),
                lat = location.lat,
                lon = location.lon,
                centerTime = centerTime,
                zoom = zoom,
                now = request.now,
                source = displaySource,
            )
        updateView(
            request,
            stateManager,
            hourlyForecasts,
            centerTime,
            displaySource,
        )
        InteractionTimingLogger.log(
            database,
            request.appWidgetId,
            request.actionTag,
            request.startTimeMs,
            request.extraMetadata,
        )
    }

    private suspend fun updateView(
        request: GraphRenderRequest,
        stateManager: WidgetStateManager,
        hourlyForecasts: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
    ) {
        val database = request.refreshContext.database
        val location = request.refreshContext.location
        val viewMode = stateManager.getViewMode(request.appWidgetId)
        val today = request.now.toLocalDate()
        val zoneId = ZoneId.systemDefault()
        val todayEpoch = today.toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val todayStartMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val weatherList =
            database.forecastDao().getForecastsInRange(
                todayEpoch,
                todayEpoch,
                location.lat,
                location.lon,
            )
        val currentTemps =
            request.repository?.getMainObservationsWithComputedNwsBlend(
                location.lat,
                location.lon,
                todayStartMs,
            ) ?: database.observationDao().getLatestMainObservations(
                location.lat,
                location.lon,
                todayStartMs,
            )
        val currentTempHourlyForecasts =
            GraphDataLoader.loadCurrentTempResolutionHourlyForecasts(
                database.hourlyForecastDao(),
                location.lat,
                location.lon,
                request.now,
            )
        val todayPrecip =
            weatherList.find { it.source == displaySource.id }?.precipProbability
        val graphStyleObservation =
            CurrentTempResolver.resolveGraphStyleCurrentTemp(
                repository = request.repository,
                lat = location.lat,
                lon = location.lon,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts,
                now = request.now,
                personalStationWeight = stateManager.getPersonalStationWeight(),
            )
        val observation =
            graphStyleObservation
                ?: ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
        CurrentTempStalenessLogger.log(
            database.appLogDao(),
            request.appWidgetId,
            viewMode,
            displaySource,
            observation,
            centerTime,
        )

        val appWidgetManager = AppWidgetManager.getInstance(request.context)
        when (viewMode) {
            ViewMode.PRECIPITATION ->
                PrecipViewHandler.updateWidget(
                    context = request.context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = request.appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = request.repository,
                    partialPush = request.partialPush,
                    origin = request.origin,
                )
            ViewMode.CLOUD_COVER ->
                CloudCoverViewHandler.updateWidget(
                    context = request.context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = request.appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = request.repository,
                    partialPush = request.partialPush,
                    origin = request.origin,
                )
            else ->
                TemperatureViewHandler.updateWidget(
                    context = request.context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = request.appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    currentTempHourlyForecasts = currentTempHourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = request.repository,
                    partialPush = request.partialPush,
                    origin = request.origin,
                )
        }
    }
}
