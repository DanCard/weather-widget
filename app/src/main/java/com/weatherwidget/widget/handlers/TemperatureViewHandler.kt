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
import com.weatherwidget.shared.graph.HeaderDeltaGate
import com.weatherwidget.widget.GraphRepaintGate
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.widget.WidgetActions
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
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        precipProbability: Int? = null,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        repository: WeatherRepository? = null,
        startupToken: String? = null,
        deferCurrentTempResolution: Boolean = false,
        // True for opportunistic "UI-only" repaints (the ~2-min now-tracking alarm). Such repaints
        // carry a now-centered narrow data window, so re-rendering an ANCHORED past/future graph from
        // them corrupts it (forecast gaps -> blank curve + missing labels). For those we update only
        // the current-temp header and leave the graph as the last full render painted it.
        uiOnly: Boolean = false,
        // Background (worker-driven) repaints push partially — no launcher re-inflate flash.
        // See WidgetViewHandler.
        partialPush: Boolean = false,
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        var gateReason: String? = null

        // Anchored view = NOW is not inside the visible window (a clicked past/future day). Its graph is
        // static, so skip the graph re-render on opportunistic UI-only updates and refresh just the
        // header current temp. Full renders (onUpdate startup, data fetch, user interaction) carry the
        // wide data window and still render the graph correctly.
        // Header-only pushes are partiallyUpdateAppWidget calls that the framework silently drops
        // until this process has backed the widget with one full updateAppWidget (its RemoteViews
        // cache resets on reboot/package-update — a fresh process). Taking the uiOnly header-only
        // shortcut before that leaves the widget_weather XML defaults ("Today / --° / --°") on the
        // launcher. So while unbacked, fall through to the full-body render (whose partial push the
        // dispatcher promotes to full) instead of a header-only partial. See [[widget_worker_partial_push]].
        val backedThisProcess = com.weatherwidget.widget.WidgetPushDispatcher.hasFullPushedThisProcess(appWidgetId)
        if (uiOnly && backedThisProcess) {
            val zoom = stateManager.getZoomLevel(appWidgetId)
            val nowForWindow = LocalDateTime.now()
            val windowEndTime = centerTime.plusHours(zoom.forwardHours)
            val nowInWindow = !nowForWindow.isBefore(centerTime.minusHours(zoom.backHours)) &&
                !nowForWindow.isAfter(windowEndTime)
            if (!nowInWindow) {
                updateHeaderCurrentTemp(
                    context, appWidgetManager, appWidgetId, stateManager, displaySource, dimensions,
                    currentTempHourlyForecasts, lastObservedTemp, observedAt, nowForWindow,
                    showDelta = HeaderDeltaGate.isWindowVisible(windowEndTime, nowForWindow),
                )
                appLogDao.log(
                    WidgetPerfLogger.TAG_WIDGET_PAINT,
                    "widget=$appWidgetId caller=TEMPERATURE state=header_only_anchored thread=${Thread.currentThread().name}",
                )
                return
            }

            // Live in-window case: gate the expensive bitmap rebuild on real change.
            val liveSmoothed = computeSmoothedForecasts(currentTempHourlyForecasts, displaySource)
            val liveResolution = CurrentTemperatureResolver.resolve(
                now = nowForWindow,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts.map { it.toHourlyForecast() },
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource),
                currentLat = stateManager.getWidgetLocation(appWidgetId)?.first
                    ?: currentTempHourlyForecasts.firstOrNull()?.locationLat
                    ?: WeatherWidgetWorker.DEFAULT_LAT,
                currentLon = stateManager.getWidgetLocation(appWidgetId)?.second
                    ?: currentTempHourlyForecasts.firstOrNull()?.locationLon
                    ?: WeatherWidgetWorker.DEFAULT_LON,
                smoothedForecasts = liveSmoothed,
            )
            val currentFormatted = liveResolution.displayTemp?.let {
                CurrentTemperatureResolver.formatDisplayTemperature(it, dimensions.cols, liveResolution.isStaleEstimate, useCelsius = stateManager.useCelsius())
            }
            val lastRender = stateManager.getLastGraphRender(appWidgetId)
            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)
            val windowSpanMinutes = zoom.totalSpanHours * 60
            val gateDecision = GraphRepaintGate.shouldRebuildBitmap(
                displayedTemp = lastRender?.displayedTemp,
                currentDisplayedTemp = currentFormatted,
                lastRenderMs = lastRender?.renderMs ?: 0L,
                nowMs = SystemClock.elapsedRealtime(),
                windowSpanMinutes = windowSpanMinutes,
                bitmapWidthPx = bitmapDims.widthPx,
            )
            gateReason = gateDecision.reason
            if (!gateDecision.shouldRebuild) {
                updateHeaderCurrentTemp(
                    context, appWidgetManager, appWidgetId, stateManager, displaySource, dimensions,
                    currentTempHourlyForecasts, lastObservedTemp, observedAt, nowForWindow,
                    showDelta = true,
                )
                appLogDao.log(
                    WidgetPerfLogger.TAG_WIDGET_PAINT,
                    "widget=$appWidgetId caller=TEMPERATURE state=header_only_live reason=${gateDecision.reason} thread=${Thread.currentThread().name}",
                )
                return
            }
        }

        val resolutionResult = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
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
            onFetchDotResolved = onFetchDotResolved,
            appLogDao = appLogDao,
        )

        val views = RemoteViews(context.packageName, com.weatherwidget.R.layout.widget_weather)
        setupDeadZoneCatchAll(context, views, appWidgetId)
        TemperatureViewBinder.bind(
            context = context,
            views = views,
            state = resolutionResult.state,
            stateManager = stateManager,
            centerTime = centerTime,
            hourlyForecasts = hourlyForecasts
        )

        if (dimensions.isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
        }

        // Reset sticky visibility from DailyViewHandler
        DailyViewHandler.bindTransientMessage(views, stateManager, appWidgetId, callerTag = "TEMPERATURE")

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=TEMPERATURE state=data push=${if (partialPush) "partial" else "full"}${gateReason?.let { " reason=$it" } ?: ""} thread=${Thread.currentThread().name}")
        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = partialPush,
            caller = "TEMPERATURE",
            appLogDao = appLogDao,
        )

        // Persist render metadata for the GraphRepaintGate on future uiOnly cycles.
        val renderedFormattedTemp = resolutionResult.currentTempResolution.displayTemp?.let {
            CurrentTemperatureResolver.formatDisplayTemperature(it, dimensions.cols, resolutionResult.currentTempResolution.isStaleEstimate, useCelsius = stateManager.useCelsius())
        }
        stateManager.setLastGraphRender(
            appWidgetId,
            WidgetStateManager.LastGraphRenderState(
                renderMs = SystemClock.elapsedRealtime(),
                displayedTemp = renderedFormattedTemp,
            ),
        )

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
                isDeltaWindowVisible = resolutionResult.isDeltaWindowVisible
            ),
            precipVisible = resolutionResult.state.header.isPrecipVisible,
            precipProbability = resolutionResult.headerPrecipProbability,
            isNowLineVisible = resolutionResult.isNowLineVisible,
            isDeltaWindowVisible = resolutionResult.isDeltaWindowVisible,
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
                    currentTempHourlyForecasts = currentTempHourlyForecasts,
                    lastObservedTemp = lastObservedTemp,
                    observedAt = observedAt,
                    currentLat = resolutionResult.lat,
                    currentLon = resolutionResult.lon,
                    numColumns = dimensions.cols,
                    widthDp = dimensions.widthDp,
                    isDeltaWindowVisible = resolutionResult.isDeltaWindowVisible,
                    quickResolution = resolutionResult.currentTempResolution,
                    storedDeltaState = storedDeltaState,
                    smoothedForecasts = resolutionResult.smoothedForecasts,
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

    /**
     * Header-only refresh for an anchored (static) graph view on an opportunistic UI update: resolve the
     * live current temp and partially update just the header temp/delta, leaving the previously-rendered
     * graph bitmap untouched. Mirrors the partial-update path in [scheduleCurrentTempRefinement].
     */
    private suspend fun updateHeaderCurrentTemp(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        displaySource: WeatherSource,
        dimensions: WidgetDimensions,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        now: LocalDateTime,
        showDelta: Boolean,
    ) {
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val lat = configuredLocation?.first ?: currentTempHourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = configuredLocation?.second ?: currentTempHourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val smoothed = computeSmoothedForecasts(currentTempHourlyForecasts, displaySource)
        val resolution = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = displaySource,
            hourlyForecasts = currentTempHourlyForecasts.map { it.toHourlyForecast() },
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource),
            currentLat = lat,
            currentLon = lon,
            smoothedForecasts = smoothed,
        )
        if (resolution.shouldClearStoredDelta) stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
        resolution.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it) }

        val partial = RemoteViews(context.packageName, com.weatherwidget.R.layout.widget_weather)
        val displayTemp = resolution.displayTemp
        partial.setTextViewText(
            com.weatherwidget.R.id.current_temp,
            displayTemp?.let { CurrentTemperatureResolver.formatDisplayTemperature(it, dimensions.cols, resolution.isStaleEstimate, useCelsius = stateManager.useCelsius()) },
        )
        partial.setViewVisibility(com.weatherwidget.R.id.current_temp, android.view.View.VISIBLE)
        val currentTempPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP, context.resources.displayMetrics,
        )
        partial.setTextViewTextSize(com.weatherwidget.R.id.current_temp, android.util.TypedValue.COMPLEX_UNIT_PX, currentTempPx)
        val appliedDelta = resolution.appliedDelta
        if (showDelta && appliedDelta != null && kotlin.math.abs(appliedDelta) >= DELTA_VISIBILITY_THRESHOLD) {
            partial.setTextViewText(com.weatherwidget.R.id.current_temp_delta, String.format("%+.1f", appliedDelta))
            val deltaPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.DELTA_TEXT_SIZE_DP, context.resources.displayMetrics,
            )
            partial.setTextViewTextSize(com.weatherwidget.R.id.current_temp_delta, android.util.TypedValue.COMPLEX_UNIT_PX, deltaPx)
            partial.setViewVisibility(com.weatherwidget.R.id.current_temp_delta, android.view.View.VISIBLE)
        } else {
            partial.setViewVisibility(com.weatherwidget.R.id.current_temp_delta, android.view.View.GONE)
        }

        val db = com.weatherwidget.data.local.WeatherDatabase.getDatabase(context)
        val errorMsg = FetchFailureIndicatorHelper.resolveFetchError(
            displaySourceId = displaySource.id,
            appLogDao = db.appLogDao(),
            lastGoodObsMs = observedAt,
        )
        FetchFailureIndicatorHelper.bind(
            context = context,
            views = partial,
            appWidgetId = appWidgetId,
            errorMessage = errorMsg,
        )

        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = partial,
            partialPush = true,
            caller = "TEMPERATURE_HEADER",
            appLogDao = db.appLogDao(),
            // Header-only: body views stay at their XML defaults, so this must never be promoted to
            // a full push. The uiOnly gate in handle() guarantees we only reach here once this
            // process has already backed the widget with a full-body push.
            bodyComplete = false,
        )
    }

    private data class CurrentTempRefinementParams(
        val context: Context,
        val appWidgetManager: AppWidgetManager,
        val appWidgetId: Int,
        val stateManager: WidgetStateManager,
        val now: LocalDateTime,
        val displaySource: WeatherSource,
        val currentTempHourlyForecasts: List<HourlyForecastEntity>,
        val lastObservedTemp: Float?,
        val observedAt: Long?,
        val currentLat: Double,
        val currentLon: Double,
        val numColumns: Int,
        val widthDp: Int,
        val isDeltaWindowVisible: Boolean,
        val quickResolution: CurrentTemperatureResolution,
        val storedDeltaState: CurrentTemperatureDeltaState?,
        val smoothedForecasts: Map<Long, Float>?,
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
                    hourlyForecasts = params.currentTempHourlyForecasts.map { it.toHourlyForecast() },
                    lastObservedTemp = params.lastObservedTemp,
                    observedAt = params.observedAt,
                    storedDeltaState = params.storedDeltaState,
                    currentLat = params.currentLat,
                    currentLon = params.currentLon,
                    smoothedForecasts = params.smoothedForecasts,
                )

            if (refined.shouldClearStoredDelta) {
                params.stateManager.clearCurrentTempDeltaState(params.appWidgetId, params.displaySource)
            }
            refined.updatedDeltaState?.let { params.stateManager.setCurrentTempDeltaState(params.appWidgetId, params.displaySource, it) }

            if (!shouldApplyRefinedHeaderUpdate(params.quickResolution, refined, params.isDeltaWindowVisible)) {
                return@launch
            }

            val partialViews = RemoteViews(appContext.packageName, com.weatherwidget.R.layout.widget_weather)
            // Re-bind just the header parts for partial update
            val displayTemp = refined.displayTemp
            val appliedDelta = refined.appliedDelta
            val formatted = displayTemp?.let {
                CurrentTemperatureResolver.formatDisplayTemperature(
                    it, params.numColumns, refined.isStaleEstimate,
                    useCelsius = params.stateManager.useCelsius(),
                )
            }
            val formattedTemp = if (formatted != null) formatted else null
            partialViews.setTextViewText(com.weatherwidget.R.id.current_temp, formattedTemp)
            partialViews.setViewVisibility(com.weatherwidget.R.id.current_temp, android.view.View.VISIBLE)
            val currentTempPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP, appContext.resources.displayMetrics)
            partialViews.setTextViewTextSize(com.weatherwidget.R.id.current_temp, android.util.TypedValue.COMPLEX_UNIT_PX, currentTempPx)

            if (appliedDelta != null && kotlin.math.abs(appliedDelta) >= DELTA_VISIBILITY_THRESHOLD && params.isDeltaWindowVisible) {
                partialViews.setTextViewText(com.weatherwidget.R.id.current_temp_delta, String.format("%+.1f", appliedDelta))
                val deltaPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.DELTA_TEXT_SIZE_DP, appContext.resources.displayMetrics)
                partialViews.setTextViewTextSize(com.weatherwidget.R.id.current_temp_delta, android.util.TypedValue.COMPLEX_UNIT_PX, deltaPx)
                partialViews.setViewVisibility(com.weatherwidget.R.id.current_temp_delta, android.view.View.VISIBLE)
            } else {
                partialViews.setViewVisibility(com.weatherwidget.R.id.current_temp_delta, android.view.View.GONE)
            }
            
            com.weatherwidget.widget.WidgetPushDispatcher.push(
                appWidgetManager = params.appWidgetManager,
                appWidgetId = params.appWidgetId,
                views = partialViews,
                partialPush = true,
                caller = "TEMPERATURE_REFINE",
                appLogDao = com.weatherwidget.data.local.WeatherDatabase.getDatabase(appContext).appLogDao(),
            )
        }
    }

    private fun shouldApplyRefinedHeaderUpdate(
        quickResolution: CurrentTemperatureResolution,
        refined: CurrentTemperatureResolution,
        isDeltaWindowVisible: Boolean,
    ): Boolean {
        val qTemp = quickResolution.displayTemp
        val rTemp = refined.displayTemp
        val tempChanged =
            when {
                qTemp == null && rTemp == null -> false
                qTemp == null || rTemp == null -> true
                else -> kotlin.math.abs(qTemp - rTemp) >= CURRENT_TEMP_FOLLOW_UP_EPSILON
            }
        val qDelta = quickResolution.appliedDelta
        val rDelta = refined.appliedDelta
        val quickDeltaVisible =
            isDeltaWindowVisible &&
                qDelta != null &&
                kotlin.math.abs(qDelta) >= DELTA_VISIBILITY_THRESHOLD
        val refinedDeltaVisible =
            isDeltaWindowVisible &&
                rDelta != null &&
                kotlin.math.abs(rDelta) >= DELTA_VISIBILITY_THRESHOLD
        val deltaChanged =
            quickDeltaVisible != refinedDeltaVisible ||
                (quickDeltaVisible &&
                    refinedDeltaVisible &&
                    kotlin.math.abs(qDelta!! - rDelta!!) >= CURRENT_TEMP_FOLLOW_UP_EPSILON)
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
                action = WidgetActions.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WidgetActions.EXTRA_UI_ONLY, true)
            })
        }
    }
}
