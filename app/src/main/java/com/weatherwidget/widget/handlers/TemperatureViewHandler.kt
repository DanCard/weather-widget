package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.CurrentTemperatureDeltaState
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.FetchDotDebug
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

object TemperatureViewHandler {
    private const val TAG = "TemperatureViewHandler"
    private const val CURRENT_TEMP_FOLLOW_UP_EPSILON = 0.05f
    private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 200L
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refinementJobs = ConcurrentHashMap<Int, Job>()
    private val fullGraphRefreshJobs = ConcurrentHashMap<Int, Job>()

    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        precipProbability: Int? = null,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        repository: WeatherRepository? = null,
        startupToken: String? = null,
        deferCurrentTempResolution: Boolean = false,
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        val resolutionResult = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            displaySource = displaySource,
            precipProbability = precipProbability,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            dimensions = dimensions,
            stateManager = stateManager,
            repository = repository,
            deferCurrentTempResolution = deferCurrentTempResolution,
            startupToken = startupToken,
            onFetchDotResolved = onFetchDotResolved
        )

        val views = RemoteViews(context.packageName, com.weatherwidget.R.layout.widget_weather)
        TemperatureViewBinder.bind(
            context = context,
            views = views,
            state = resolutionResult.state,
            stateManager = stateManager,
            centerTime = centerTime,
            hourlyForecasts = hourlyForecasts
        )

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=TEMPERATURE state=data thread=${Thread.currentThread().name}")
        appWidgetManager.updateAppWidget(appWidgetId, views)

        val headerLog = buildHeaderStateLog(
            widgetId = appWidgetId,
            viewMode = com.weatherwidget.widget.ViewMode.TEMPERATURE,
            displaySource = displaySource,
            configuredLocation = stateManager.getWidgetLocation(appWidgetId),
            dataLat = resolutionResult.lat,
            dataLon = resolutionResult.lon,
            dimensions = dimensions,
            currentTemp = resolutionResult.currentTempResolution.displayTemp,
            estimatedTemp = resolutionResult.currentTempResolution.estimatedTemp,
            observedTemp = resolutionResult.currentTempResolution.observedTemp,
            appliedDelta = resolutionResult.currentTempResolution.appliedDelta,
            deltaVisible = resolutionResult.state.header.isDeltaVisible,
            deltaHiddenReason = temperatureDeltaHiddenReason(
                currentTemp = resolutionResult.currentTempResolution.displayTemp,
                appliedDelta = resolutionResult.currentTempResolution.appliedDelta,
                isNowLineVisible = resolutionResult.isNowLineVisible
            ),
            precipVisible = resolutionResult.state.header.isPrecipVisible,
            precipProbability = resolutionResult.headerPrecipProbability,
            isNowLineVisible = resolutionResult.isNowLineVisible,
            offset = resolutionResult.state.hourlyOffset,
            zoom = resolutionResult.state.zoom,
            resolveMs = resolutionResult.resolveMs
        )
        appLogDao.log(TAG, headerLog)

        if (!deferCurrentTempResolution) {
            val currentTempResolution = resolutionResult.currentTempResolution
            if (currentTempResolution.shouldClearStoredDelta) {
                stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
            }
            currentTempResolution.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it) }
        } else {
            val storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource)
            scheduleCurrentTempRefinement(
                CurrentTempRefinementParams(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    stateManager = stateManager,
                    now = LocalDateTime.now(),
                    displaySource = displaySource,
                    hourlyForecasts = hourlyForecasts,
                    lastObservedTemp = lastObservedTemp,
                    observedAt = observedAt,
                    currentLat = resolutionResult.lat,
                    currentLon = resolutionResult.lon,
                    numColumns = dimensions.cols,
                    widthDp = dimensions.widthDp,
                    isNowLineVisible = resolutionResult.isNowLineVisible,
                    quickResolution = resolutionResult.currentTempResolution,
                    storedDeltaState = storedDeltaState,
                )
            )
        }

        if (startupToken != null && resolutionResult.state.graph.useGraph) {
            scheduleStartupFullGraphRefresh(context, appWidgetId, handlerStartMs)
        }

        val totalMs = SystemClock.elapsedRealtime() - handlerStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = appLogDao,
            thresholdMs = WidgetPerfLogger.PIPELINE_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_TEMP_PIPELINE_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to "TEMPERATURE",
                "useGraph" to resolutionResult.state.graph.useGraph,
                "startupFastPath" to (startupToken != null && resolutionResult.state.graph.useGraph),
                "resolveMs" to resolutionResult.resolveMs,
                "obsQueryMs" to resolutionResult.obsQueryMs,
                "buildHourDataMs" to resolutionResult.buildHourDataMs,
                "renderMs" to resolutionResult.renderMs,
                "hours" to resolutionResult.state.graph.hourData.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    private data class CurrentTempRefinementParams(
        val context: Context,
        val appWidgetManager: AppWidgetManager,
        val appWidgetId: Int,
        val stateManager: WidgetStateManager,
        val now: LocalDateTime,
        val displaySource: WeatherSource,
        val hourlyForecasts: List<HourlyForecastEntity>,
        val lastObservedTemp: Float?,
        val observedAt: Long?,
        val currentLat: Double,
        val currentLon: Double,
        val numColumns: Int,
        val widthDp: Int,
        val isNowLineVisible: Boolean,
        val quickResolution: CurrentTemperatureResolution,
        val storedDeltaState: CurrentTemperatureDeltaState?,
    )

    private fun scheduleCurrentTempRefinement(
        params: CurrentTempRefinementParams,
    ) {
        val appContext = params.context.applicationContext
        refinementJobs[params.appWidgetId]?.cancel()
        refinementJobs[params.appWidgetId] = asyncScope.launch {
            val refined =
                CurrentTemperatureResolver.resolve(
                    now = params.now,
                    displaySource = params.displaySource,
                    hourlyForecasts = params.hourlyForecasts,
                    lastObservedTemp = params.lastObservedTemp,
                    observedAt = params.observedAt,
                    storedDeltaState = params.storedDeltaState,
                    currentLat = params.currentLat,
                    currentLon = params.currentLon,
                )

            if (refined.shouldClearStoredDelta) {
                params.stateManager.clearCurrentTempDeltaState(params.appWidgetId, params.displaySource)
            }
            refined.updatedDeltaState?.let { params.stateManager.setCurrentTempDeltaState(params.appWidgetId, params.displaySource, it) }

            if (!shouldApplyRefinedHeaderUpdate(params.quickResolution, refined, params.isNowLineVisible)) {
                return@launch
            }

            val partialViews = RemoteViews(appContext.packageName, com.weatherwidget.R.layout.widget_weather)
            // Re-bind just the header parts for partial update
            val formattedTemp = CurrentTemperatureResolver.formatDisplayTemperature(
                refined.displayTemp!!, params.numColumns, refined.isStaleEstimate
            )
            partialViews.setTextViewText(com.weatherwidget.R.id.current_temp, formattedTemp)
            partialViews.setViewVisibility(com.weatherwidget.R.id.current_temp, android.view.View.VISIBLE)
            val tempTextSizeSp = if (params.widthDp < 420) 22f else 26f
            partialViews.setTextViewTextSize(com.weatherwidget.R.id.current_temp, android.util.TypedValue.COMPLEX_UNIT_SP, tempTextSizeSp)

            if (refined.appliedDelta != null && kotlin.math.abs(refined.appliedDelta) >= DELTA_VISIBILITY_THRESHOLD && params.isNowLineVisible) {
                partialViews.setTextViewText(com.weatherwidget.R.id.current_temp_delta, String.format("%+.1f", refined.appliedDelta))
                partialViews.setViewVisibility(com.weatherwidget.R.id.current_temp_delta, android.view.View.VISIBLE)
            } else {
                partialViews.setViewVisibility(com.weatherwidget.R.id.current_temp_delta, android.view.View.GONE)
            }
            
            params.appWidgetManager.partiallyUpdateAppWidget(params.appWidgetId, partialViews)
        }
    }

    private fun shouldApplyRefinedHeaderUpdate(
        quickResolution: CurrentTemperatureResolution,
        refined: CurrentTemperatureResolution,
        isNowLineVisible: Boolean,
    ): Boolean {
        val tempChanged =
            when {
                quickResolution.displayTemp == null && refined.displayTemp == null -> false
                quickResolution.displayTemp == null || refined.displayTemp == null -> true
                else -> kotlin.math.abs(quickResolution.displayTemp - refined.displayTemp) >= CURRENT_TEMP_FOLLOW_UP_EPSILON
            }
        val quickDeltaVisible =
            isNowLineVisible &&
                quickResolution.appliedDelta != null &&
                kotlin.math.abs(quickResolution.appliedDelta) >= DELTA_VISIBILITY_THRESHOLD
        val refinedDeltaVisible =
            isNowLineVisible &&
                refined.appliedDelta != null &&
                kotlin.math.abs(refined.appliedDelta) >= DELTA_VISIBILITY_THRESHOLD
        val deltaChanged =
            quickDeltaVisible != refinedDeltaVisible ||
                (quickDeltaVisible &&
                    refinedDeltaVisible &&
                    kotlin.math.abs(quickResolution.appliedDelta - refined.appliedDelta) >= CURRENT_TEMP_FOLLOW_UP_EPSILON)
        return tempChanged || deltaChanged || quickResolution.isStaleEstimate != refined.isStaleEstimate
    }

    private fun scheduleStartupFullGraphRefresh(
        context: Context,
        appWidgetId: Int,
        phase1StartMs: Long,
    ) {
        val appContext = context.applicationContext
        fullGraphRefreshJobs[appWidgetId]?.cancel()
        fullGraphRefreshJobs[appWidgetId] = asyncScope.launch {
            val appLogDao = WeatherDatabase.getDatabase(appContext).appLogDao()
            try {
                delay(STARTUP_FULL_GRAPH_REFRESH_DELAY_MS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            appContext.sendBroadcast(Intent(appContext, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
            })
        }
    }
}
