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
import com.weatherwidget.widget.GraphRepaintGate
import com.weatherwidget.widget.ObservationWatermark
import com.weatherwidget.widget.WidgetActionReceiver
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
        origin: com.weatherwidget.widget.WidgetPushDispatcher.Origin = com.weatherwidget.widget.WidgetPushDispatcher.Origin.UNSPECIFIED,
        // See CloudCoverViewHandler: a stale source snapshot in the loader, not a real data gap.
        sourceMissingFromLoad: Boolean = false,
        // Newest observation time among the rows this source draws; drives GraphRepaintGate's
        // data-changed check. See ObservationWatermark.
        //
        // Null means "this caller did not measure it" — the interaction path (nav taps, refresh)
        // renders unconditionally and never consults the gate, so it has no watermark to offer.
        // Such a render must PRESERVE the stored value rather than stamp NONE over it: the stored
        // one was measured by the same query the next gated pass will use, and overwriting it with
        // a value from a different query makes the two incomparable.
        dataWatermarkMs: Long? = null,
        // A repaint was skipped outright for screen-off; force a full rebuild. See WidgetPaintCoordinator.
        paintOwed: Boolean = false,
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
            val zoom = stateManager.getZoomWindow(appWidgetId)
            val nowForWindow = LocalDateTime.now()
            val windowEndTime = centerTime.plusHours(zoom.forwardHours)
            val nowInWindow = !nowForWindow.isBefore(centerTime.minusHours(zoom.backHours)) &&
                !nowForWindow.isAfter(windowEndTime)
            if (!nowInWindow) {
                updateHeaderCurrentTemp(
                    context, appWidgetManager, appWidgetId, stateManager, displaySource, dimensions,
                    currentTempHourlyForecasts, lastObservedTemp, observedAt, nowForWindow,
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
                    ?: Double.NaN,
                currentLon = stateManager.getWidgetLocation(appWidgetId)?.second
                    ?: currentTempHourlyForecasts.firstOrNull()?.locationLon
                    ?: Double.NaN,
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
                lastWatermarkMs = lastRender?.dataWatermarkMs,
                currentWatermarkMs = dataWatermarkMs ?: ObservationWatermark.NONE,
                paintOwed = paintOwed,
            )
            gateReason = gateDecision.reason
            if (!gateDecision.shouldRebuild) {
                updateHeaderCurrentTemp(
                    context, appWidgetManager, appWidgetId, stateManager, displaySource, dimensions,
                    currentTempHourlyForecasts, lastObservedTemp, observedAt, nowForWindow,
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
            sourceMissingFromLoad = sourceMissingFromLoad,
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

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=TEMPERATURE origin=${origin.name} state=data push=${if (partialPush) "partial" else "full"}${gateReason?.let { " reason=$it" } ?: ""} thread=${Thread.currentThread().name}")
        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = partialPush,
            caller = "TEMPERATURE",
            appLogDao = appLogDao,
            origin = origin,
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
                dataWatermarkMs = dataWatermarkMs
                    ?: stateManager.getLastGraphRender(appWidgetId)?.dataWatermarkMs,
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
            headerDelta = resolutionResult.deltaFromYesterday,
            deltaVisible = resolutionResult.state.header.isDeltaVisible,
            deltaHiddenReason = temperatureDeltaHiddenReason(
                currentTemp = resolutionResult.currentTempResolution.displayTemp,
                delta = resolutionResult.deltaFromYesterday,
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
                    currentTempHourlyForecasts = currentTempHourlyForecasts,
                    lastObservedTemp = lastObservedTemp,
                    observedAt = observedAt,
                    currentLat = resolutionResult.lat,
                    currentLon = resolutionResult.lon,
                    numColumns = dimensions.cols,
                    widthDp = dimensions.widthDp,
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
    ) {
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        // NaN rather than a hardcoded coordinate: the configured location is the primary source here,
        // and if neither it nor the hourly rows supply one there is nothing to resolve a current temp
        // against. See SunPositionUtils.UNKNOWN_LOCATION for how the decoration degrades.
        val lat = configuredLocation?.first ?: currentTempHourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
        val lon = configuredLocation?.second ?: currentTempHourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN
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
        // The header delta (delta from yesterday) is intentionally NOT touched here: it is
        // pan-independent and always shown, so this header-only partial leaves the view exactly as
        // the last full render painted it. A partial push only applies the views it sets, so the
        // delta persists untouched instead of flickering on a value this path cannot recompute
        // (the yesterday delta needs the observation window, which only full renders load).

        val db = com.weatherwidget.data.local.WeatherDatabase.getDatabase(context)
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
            origin = com.weatherwidget.widget.WidgetPushDispatcher.Origin.UI_ONLY,
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

            if (!shouldApplyRefinedHeaderUpdate(params.quickResolution, refined)) {
                return@launch
            }

            val partialViews = RemoteViews(appContext.packageName, com.weatherwidget.R.layout.widget_weather)
            // Re-bind just the header parts for partial update
            val displayTemp = refined.displayTemp
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

            // Header delta (delta from yesterday) is left untouched, same as updateHeaderCurrentTemp:
            // it always shows and is only repainted by full renders, which own the observation data.

            com.weatherwidget.widget.WidgetPushDispatcher.push(
                appWidgetManager = params.appWidgetManager,
                appWidgetId = params.appWidgetId,
                views = partialViews,
                partialPush = true,
                caller = "TEMPERATURE_REFINE",
                appLogDao = com.weatherwidget.data.local.WeatherDatabase.getDatabase(appContext).appLogDao(),
                origin = com.weatherwidget.widget.WidgetPushDispatcher.Origin.UI_ONLY,
            )
        }
    }

    private fun shouldApplyRefinedHeaderUpdate(
        quickResolution: CurrentTemperatureResolution,
        refined: CurrentTemperatureResolution,
    ): Boolean {
        val qTemp = quickResolution.displayTemp
        val rTemp = refined.displayTemp
        val tempChanged =
            when {
                qTemp == null && rTemp == null -> false
                qTemp == null || rTemp == null -> true
                else -> kotlin.math.abs(qTemp - rTemp) >= CURRENT_TEMP_FOLLOW_UP_EPSILON
            }
        // No delta comparison: the header delta (yesterday delta) is not repainted by this partial
        // path at all, so a delta change can never be a reason to push it.
        return tempChanged || quickResolution.isStaleEstimate != refined.isStaleEstimate
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
            appContext.sendBroadcast(Intent(appContext, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WidgetActions.EXTRA_UI_ONLY, true)
            })
        }
    }
}
