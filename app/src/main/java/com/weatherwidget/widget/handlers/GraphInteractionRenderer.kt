package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.BatterySnapshotProvider
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
        val battery = BatterySnapshotProvider.snapshot(context)
        return SourceStalenessProbe.sourceNeedsRefresh(
            state,
            nowMs,
            com.weatherwidget.data.remote.ForecastHorizonCadence.isHighBattery(battery.isCharging, battery.batteryLevel),
        )
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
                appLogDao = database.appLogDao(),
            )
        updateView(
            request,
            stateManager,
            hourlyForecasts,
            centerTime,
            displaySource,
        )
        com.weatherwidget.widget.ViewingForecastRefresher.onRender(context, request.appWidgetId, request.origin)
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
        val weatherList =
            database.forecastDao().getForecastsInRange(
                todayEpoch,
                todayEpoch,
                location.lat,
                location.lon,
            )
        val currentTempInputHourly =
            GraphDataLoader.loadCurrentTempInputHourlyForecasts(
                database.hourlyForecastDao(),
                database.hourlyForecastHistoryDao(),
                displaySource,
                location.lat,
                location.lon,
                request.now,
            )
        val currentTempHourlyForecasts = GraphDataLoader.nowCentred(currentTempInputHourly, request.now)
        val todayPrecip =
            weatherList.find { it.source == displaySource.id }?.precipProbability
        val observation =
            CurrentTempResolver.observe(
                repository = request.repository,
                lat = location.lat,
                lon = location.lon,
                displaySource = displaySource,
                hourlyForecasts = GraphDataLoader.currentTempInput(currentTempInputHourly, request.now),
                now = request.now,
                personalStationWeight = stateManager.getPersonalStationWeight(),
            )
        CurrentTempStalenessLogger.log(
            database.appLogDao(),
            request.appWidgetId,
            viewMode,
            displaySource,
            observation,
            centerTime,
        )

        val appWidgetManager = AppWidgetManager.getInstance(request.context)
        val paintInputs = PaintInputs(
            appWidgetManager = appWidgetManager,
            viewMode = viewMode,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            centerTime = centerTime,
            displaySource = displaySource,
            todayPrecip = todayPrecip,
            observation = observation,
        )

        paint(request, paintInputs, request.partialPush)
    }

    /** Everything a paint needs, loaded once. */
    private data class PaintInputs(
        val appWidgetManager: AppWidgetManager,
        val viewMode: ViewMode,
        val hourlyForecasts: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        val currentTempHourlyForecasts: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        val centerTime: LocalDateTime,
        val displaySource: WeatherSource,
        val todayPrecip: Int?,
        val observation: ObservationResolver.ObservedCurrentTemperature?,
    )

    private suspend fun paint(
        request: GraphRenderRequest,
        inputs: PaintInputs,
        partialPush: Boolean,
    ) {
        when (inputs.viewMode) {
            ViewMode.PRECIPITATION ->
                PrecipViewHandler.updateWidget(
                    context = request.context,
                    appWidgetManager = inputs.appWidgetManager,
                    appWidgetId = request.appWidgetId,
                    hourlyForecasts = inputs.hourlyForecasts,
                    currentTempHourlyForecasts = inputs.currentTempHourlyForecasts,
                    centerTime = inputs.centerTime,
                    precipProbability = inputs.todayPrecip,
                    lastObservedTemp = inputs.observation?.temperature,
                    observedAt = inputs.observation?.observedAt,
                    repository = request.repository,
                    partialPush = partialPush,
                    origin = request.origin,
                )
            ViewMode.CLOUD_COVER ->
                CloudCoverViewHandler.updateWidget(
                    context = request.context,
                    appWidgetManager = inputs.appWidgetManager,
                    appWidgetId = request.appWidgetId,
                    hourlyForecasts = inputs.hourlyForecasts,
                    currentTempHourlyForecasts = inputs.currentTempHourlyForecasts,
                    centerTime = inputs.centerTime,
                    displaySource = inputs.displaySource,
                    precipProbability = inputs.todayPrecip,
                    lastObservedTemp = inputs.observation?.temperature,
                    observedAt = inputs.observation?.observedAt,
                    repository = request.repository,
                    partialPush = partialPush,
                    origin = request.origin,
                )
            else ->
                TemperatureViewHandler.updateWidget(
                    context = request.context,
                    appWidgetManager = inputs.appWidgetManager,
                    appWidgetId = request.appWidgetId,
                    hourlyForecasts = inputs.hourlyForecasts,
                    currentTempHourlyForecasts = inputs.currentTempHourlyForecasts,
                    centerTime = inputs.centerTime,
                    displaySource = inputs.displaySource,
                    precipProbability = inputs.todayPrecip,
                    lastObservedTemp = inputs.observation?.temperature,
                    observedAt = inputs.observation?.observedAt,
                    deltaFromYesterday = inputs.observation?.deltaFromYesterday,
                    repository = request.repository,
                    partialPush = partialPush,
                    origin = request.origin,
                )
        }
    }
}
