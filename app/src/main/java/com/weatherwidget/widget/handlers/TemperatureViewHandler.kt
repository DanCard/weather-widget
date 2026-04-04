package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureDeltaState
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

object TemperatureViewHandler {
    private const val TAG = "TemperatureViewHandler"
    private const val CELL_HEIGHT_DP = 90
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 12
    private const val CURRENT_TEMP_FOLLOW_UP_EPSILON = 0.05f
    private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 200L
    private const val GRAPH_MIN_ROWS = 1.4f
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refinementJobs = ConcurrentHashMap<Int, Job>()
    private val fullGraphRefreshJobs = ConcurrentHashMap<Int, Job>()
    private val CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

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
        onFetchDotResolved: ((TemperatureGraphRenderer.FetchDotDebug) -> Unit)? = null,
        repository: WeatherRepository? = null,
        startupToken: String? = null,
        deferCurrentTempResolution: Boolean = false,
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        val stateManager = WidgetStateManager(context)
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

        views.setViewVisibility(R.id.graph_day_zones, View.GONE)

        val zoom = stateManager.getZoomLevel(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        setupNavigationButtons(context, views, appWidgetId, stateManager)

        setupCurrentTempToggle(context, views, appWidgetId)
        setupHomeShortcut(context, views, appWidgetId)
        setupSettingsShortcut(context, views, appWidgetId)

        val warning = ApiSourceWarningHelper.resolveBlockingSourceWarning(
            appLogDao = appLogDao,
            displaySource = displaySource,
            hasSelectedSourceData = hourlyForecasts.any { it.source == displaySource.id },
        )
        if (warning != null) {
            ApiSourceWarningHelper.renderSourceWarningState(context, views, appWidgetId, warning)
            setupApiToggle(context, views, appWidgetId, numRows)
            appLogDao.log(
                "TEMP_SOURCE_BLOCKED",
                "widget=$appWidgetId source=${displaySource.id} message=${warning.toastMessage}",
                "WARN",
            )
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=TEMP state=warning thread=${Thread.currentThread().name}")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }
        ApiSourceWarningHelper.hideSourceWarning(views)

        val dayName = centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val sourceIndicator = if (centerTime.toLocalDate() == LocalDateTime.now().toLocalDate()) {
            displaySource.shortDisplayName
        } else {
            "$dayName • ${displaySource.shortDisplayName}"
        }
        views.setTextViewText(R.id.api_source, sourceIndicator)

        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val isNight = SunPositionUtils.isNight(now, lat, lon)

        val currentHourForecast = getCurrentHourForecast(hourlyForecasts, displaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = isNight,
            cloudCover = currentHourForecast?.cloudCover,
        )
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        val goCloudIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetIntentRouter.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, ViewMode.CLOUD_COVER.name)
        }
        val goCloudPending = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.iconViewToggle(appWidgetId), goCloudIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, goCloudPending)

        setupApiToggle(context, views, appWidgetId, numRows)

        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource)

        setupCurrentStationsShortcut(context, views, appWidgetId)

        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= GRAPH_MIN_ROWS
        val deferStartupGraphActuals = startupToken != null && useGraph

        val database = WeatherDatabase.getDatabase(context)

        val smoothedForecasts = computeSmoothedForecasts(hourlyForecasts, displaySource)

        val graphLoadResult = loadGraphHours(
            context = context,
            appWidgetId = appWidgetId,
            database = database,
            stateManager = stateManager,
            repository = repository,
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            numColumns = numColumns,
            displaySource = displaySource,
            zoom = zoom,
            lat = lat,
            lon = lon,
            useGraph = useGraph,
            deferStartupGraphActuals = deferStartupGraphActuals,
            smoothedForecasts = smoothedForecasts,
        )
        val graphHours: List<TemperatureGraphRenderer.HourData>
        val obsQueryMs: Long
        val buildHourDataMs: Long
        when (graphLoadResult) {
            is GraphLoadOutcome.Empty -> {
                appWidgetManager.updateAppWidget(appWidgetId, views)
                val totalMs = SystemClock.elapsedRealtime() - handlerStartMs
                WidgetPerfLogger.logIfSlow(
                    appLogDao = database.appLogDao(),
                    thresholdMs = WidgetPerfLogger.PIPELINE_SLOW_MS,
                    totalMs = totalMs,
                    appLogTag = WidgetPerfLogger.TAG_TEMP_PIPELINE_PERF,
                    message = WidgetPerfLogger.kv(
                        "token" to startupToken,
                        "widget" to appWidgetId,
                        "view" to "TEMPERATURE",
                        "useGraph" to useGraph,
                        "startupFastPath" to deferStartupGraphActuals,
                        "emptyReason" to graphLoadResult.reason,
                        "totalMs" to totalMs,
                    ),
                    debugTag = TAG,
                )
                return
            }
            is GraphLoadOutcome.Loaded -> {
                graphHours = graphLoadResult.hours
                obsQueryMs = graphLoadResult.obsQueryMs
                buildHourDataMs = graphLoadResult.buildHourDataMs
            }
        }
        var renderMs = 0L

        val storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource)

        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution =
            if (deferCurrentTempResolution) {
                val quick =
                    CurrentTemperatureResolver.resolveQuick(
                        now = now,
                        displaySource = displaySource,
                        hourlyForecasts = hourlyForecasts,
                        lastObservedTemp = lastObservedTemp,
                        smoothedForecasts = smoothedForecasts,
                    )
                CurrentTemperatureResolution(
                    displayTemp = quick.displayTemp,
                    estimatedTemp = quick.estimatedTemp,
                    observedTemp = quick.observedTemp,
                    isStaleEstimate = quick.isStaleEstimate,
                    appliedDelta = null,
                    updatedDeltaState = null,
                    shouldClearStoredDelta = false,
                )
            } else {
                CurrentTemperatureResolver.resolve(
                    now = now,
                    displaySource = displaySource,
                    hourlyForecasts = hourlyForecasts,
                    lastObservedTemp = lastObservedTemp,
                    observedAt = observedAt,
                    storedDeltaState = storedDeltaState,
                    currentLat = lat,
                    currentLon = lon,
                    smoothedForecasts = smoothedForecasts,
                )
            }
        val resolveMs = SystemClock.elapsedRealtime() - resolveStartMs
        if (!deferCurrentTempResolution) {
            if (currentTempResolution.shouldClearStoredDelta) {
                stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
            }
            currentTempResolution.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it) }
        }

        val currentTemp = currentTempResolution.displayTemp
        val isNowLineVisible = graphHours.any { it.isCurrentHour }
        val delta = currentTempResolution.appliedDelta
        val deltaVisible =
            currentTemp != null &&
                isNowLineVisible &&
                delta != null &&
                kotlin.math.abs(delta) >= DELTA_VISIBILITY_THRESHOLD

        applyCurrentTempHeader(
            views = views,
            currentTemp = currentTemp,
            numColumns = numColumns,
            widthDp = dimensions.widthDp,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
            appliedDelta = delta,
            isNowLineVisible = isNowLineVisible,
        )

        val headerPrecipProbability =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = precipProbability,
                referenceTime = centerTime,
            )

        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)
        if (isPrecipVisible) {
            views.setTextViewText(R.id.precip_probability, "$headerPrecipProbability%")
            val textSizeSp = HeaderPrecipCalculator.getPrecipTextSize(checkNotNull(headerPrecipProbability))
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible)

        positionCenterIcons(
            views = views,
            widthDp = dimensions.widthDp,
            isPrecipVisible = isPrecipVisible,
        )

        database.appLogDao().log(
            TAG,
            buildHeaderStateLog(
                widgetId = appWidgetId,
                viewMode = ViewMode.TEMPERATURE,
                displaySource = displaySource,
                configuredLocation = configuredLocation,
                dataLat = lat,
                dataLon = lon,
                dimensions = dimensions,
                currentTemp = currentTemp,
                estimatedTemp = currentTempResolution.estimatedTemp,
                observedTemp = currentTempResolution.observedTemp,
                appliedDelta = delta,
                deltaVisible = deltaVisible,
                deltaHiddenReason = temperatureDeltaHiddenReason(currentTemp, delta, isNowLineVisible),
                precipVisible = isPrecipVisible,
                precipProbability = headerPrecipProbability,
                isNowLineVisible = isNowLineVisible,
                offset = hourlyOffset,
                zoom = zoom,
                resolveMs = resolveMs,
            ),
        )

        if (useGraph) {
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16

            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)
            val rawWidthPx = WidgetSizeCalculator.dpToPx(context, widthDp).coerceAtLeast(1)
            val rawHeightPx = WidgetSizeCalculator.dpToPx(context, heightDp).coerceAtLeast(1)
            val bitmapScale =
                min(
                    widthPx.toFloat() / rawWidthPx.toFloat(),
                    heightPx.toFloat() / rawHeightPx.toFloat(),
                )

            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = try {
                TemperatureGraphRenderer.renderGraph(
                    context = context,
                    hours = graphHours,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    currentTime = now,
                    bitmapScale = bitmapScale,
                    appliedDelta = if (isNowLineVisible) currentTempResolution.appliedDelta else null,
                    observedAt = observedAt,
                    onFetchDotResolved = onFetchDotResolved,
                )
            } catch (e: Exception) {
                Log.e(TAG, "renderGraph failed for widget=$appWidgetId size=${widthPx}x${heightPx}", e)
                database.appLogDao().log("TEMP_RENDER_ERROR", "widget=$appWidgetId error=${e.message}")
                null
            }
            renderMs = SystemClock.elapsedRealtime() - renderStartMs

            if (bitmap != null) {
                if (renderMs > 100) {
                    database.appLogDao().log(
                        "TEMP_RENDER_SLOW",
                        "widget=$appWidgetId renderMs=${renderMs}ms size=${widthPx}x${heightPx}",
                    )
                }
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)
                views.setViewVisibility(R.id.graph_bottom_zone, View.VISIBLE)
                views.setImageViewBitmap(R.id.graph_view, bitmap)

                HourlyBottomZoneHelper.setup(
                    context = context,
                    views = views,
                    appWidgetId = appWidgetId,
                    hourIconResources = graphHours.map { it.iconRes },
                    currentViewMode = ViewMode.TEMPERATURE,
                    zoom = zoom,
                    hourlyOffset = hourlyOffset,
                )
            } else {
                views.setViewVisibility(R.id.text_container, View.VISIBLE)
                views.setViewVisibility(R.id.graph_view, View.GONE)
                views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
                views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
                views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
                views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
                views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)

                updateHourlyTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
            }
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)

            updateHourlyTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
        }

        WeatherDatabase.getDatabase(context).appLogDao().log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=TEMPERATURE state=data thread=${Thread.currentThread().name}")
        appWidgetManager.updateAppWidget(appWidgetId, views)
        if (deferCurrentTempResolution) {
            scheduleCurrentTempRefinement(
                CurrentTempRefinementParams(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    stateManager = stateManager,
                    now = now,
                    displaySource = displaySource,
                    hourlyForecasts = hourlyForecasts,
                    lastObservedTemp = lastObservedTemp,
                    observedAt = observedAt,
                    currentLat = lat,
                    currentLon = lon,
                    numColumns = numColumns,
                    widthDp = dimensions.widthDp,
                    isNowLineVisible = isNowLineVisible,
                    quickResolution = currentTempResolution,
                    storedDeltaState = storedDeltaState,
                )
            )
        }
        if (deferStartupGraphActuals) {
            scheduleStartupFullGraphRefresh(context, appWidgetId, handlerStartMs)
        }
        val totalMs = SystemClock.elapsedRealtime() - handlerStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
            thresholdMs = WidgetPerfLogger.PIPELINE_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_TEMP_PIPELINE_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to "TEMPERATURE",
                "useGraph" to useGraph,
                "startupFastPath" to deferStartupGraphActuals,
                "resolveMs" to resolveMs,
                "obsQueryMs" to obsQueryMs,
                "buildHourDataMs" to buildHourDataMs,
                "renderMs" to renderMs,
                "hours" to graphHours.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    private sealed class GraphLoadOutcome {
        data class Empty(val reason: String) : GraphLoadOutcome()
        data class Loaded(
            val hours: List<TemperatureGraphRenderer.HourData>,
            val obsQueryMs: Long,
            val buildHourDataMs: Long,
        ) : GraphLoadOutcome()
    }

    private suspend fun loadGraphHours(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        repository: WeatherRepository?,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: ZoomLevel,
        lat: Double,
        lon: Double,
        useGraph: Boolean,
        deferStartupGraphActuals: Boolean,
        smoothedForecasts: Map<Long, Float>,
    ): GraphLoadOutcome {
        if (!useGraph) return GraphLoadOutcome.Loaded(emptyList(), 0L, 0L)

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated

        var obsQueryMs = 0L
        val observations =
            if (deferStartupGraphActuals) {
                Log.d(TAG, "updateWidget: widget=$appWidgetId startup graph fast path, skipping actual observation query")
                emptyList()
            } else {
                val minEpoch = alignedCenter.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val maxEpoch = alignedCenter.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val obsStartMs = SystemClock.elapsedRealtime()
                val loaded = repository?.getObservationsInRange(minEpoch, maxEpoch, lat, lon) ?: emptyList()
                val afterObsMs = SystemClock.elapsedRealtime()
                obsQueryMs = afterObsMs - obsStartMs
                Log.d(TAG, "updateWidget: widget=$appWidgetId observations=${loaded.size}, zoom=$zoom")

                val queryStart = alignedCenter.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS)
                val queryEnd = alignedCenter.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS)
                maybeEnqueueHourlyObservationBackfill(
                    context = context,
                    database = database,
                    stateManager = stateManager,
                    appWidgetId = appWidgetId,
                    displaySource = displaySource,
                    graphStart = queryStart,
                    graphEnd = queryEnd,
                    observations = loaded,
                    repositoryPresent = repository != null,
                )
                loaded
            }

        val buildHourDataStartMs = SystemClock.elapsedRealtime()
        val blendDebugCollector = BlendDebugCollector()
        val hourDataResult = buildHourDataResult(
            hourlyForecasts,
            centerTime,
            numColumns,
            displaySource,
            zoom,
            observations,
            onBlendDebug = { lineProvider -> blendDebugCollector.recordDetailed(lineProvider) },
            smoothedForecasts = smoothedForecasts,
        )
        val hourData = hourDataResult.hours
        val buildHourDataMs = SystemClock.elapsedRealtime() - buildHourDataStartMs
        val actualCount = hourData.count { it.isActual }
        Log.d(TAG, "updateWidget: widget=$appWidgetId hours=${hourData.size}, actualHours=$actualCount")

        if (hourData.isEmpty() && hourlyForecasts.isNotEmpty()) {
            Log.w(TAG, "buildHourDataResult returned empty despite ${hourlyForecasts.size} hourly rows — " +
                "centerTime=$centerTime zoom=$zoom source=$displaySource, skipping bitmap update")
            return GraphLoadOutcome.Empty("buildHourDataResult_empty")
        }

        if (!deferStartupGraphActuals) {
            val stationIds = observations
                .filter { matchesObservationSource(it, displaySource) }
                .map { it.stationId }.toSet()
            database.appLogDao().log(
                "IDW_BLEND",
                "source=${displaySource.id} stations=${stationIds.size} [${stationIds.joinToString(",")}] blendedPoints=$actualCount",
            )
            blendDebugCollector.emittedLines()
                .take(MAX_PERSISTED_BLEND_DEBUG_LINES)
                .forEach { line ->
                    database.appLogDao().log("TEMP_ACTUALS_DEBUG", line)
                }
            if (blendDebugCollector.emittedLines().size > MAX_PERSISTED_BLEND_DEBUG_LINES) {
                database.appLogDao().log(
                    "TEMP_ACTUALS_DEBUG",
                    "omitted=${blendDebugCollector.emittedLines().size - MAX_PERSISTED_BLEND_DEBUG_LINES} additional emitted blend debug lines",
                )
            }
            database.appLogDao().log(
                "TEMP_ACTUALS_DEBUG",
                "summary " + blendDebugCollector.buildSummary(
                    stationCount = stationIds.size,
                    blendedPointCount = actualCount,
                    blendDurationMs = buildHourDataMs,
                ),
            )
            hourDataResult.blendStats?.let { stats ->
                database.appLogDao().log(
                    "TEMP_ACTUALS_PERF",
                    "widget=$appWidgetId source=${displaySource.id} buildMs=$buildHourDataMs ${stats.summary()}",
                )
            }
            val obsBlendMs = obsQueryMs + buildHourDataMs
            if (obsBlendMs > 100) {
                database.appLogDao().log(
                    "TEMP_OBS_SLOW",
                    "widget=$appWidgetId obsQuery=${obsQueryMs}ms blend=${buildHourDataMs}ms total=${obsBlendMs}ms " +
                        hourDataResult.blendStats?.summary(topStations = 2).orEmpty(),
                )
            }
        } else {
            database.appLogDao().log(
                "TEMP_STARTUP_FAST_PATH",
                "widget=$appWidgetId source=${displaySource.id} startup graph skipped observation blending",
            )
        }

        return GraphLoadOutcome.Loaded(
            hours = hourData,
            obsQueryMs = obsQueryMs,
            buildHourDataMs = buildHourDataMs,
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

            val partialViews = RemoteViews(appContext.packageName, R.layout.widget_weather)
            applyCurrentTempHeader(
                views = partialViews,
                currentTemp = refined.displayTemp,
                numColumns = params.numColumns,
                widthDp = params.widthDp,
                isStaleEstimate = refined.isStaleEstimate,
                appliedDelta = refined.appliedDelta,
                isNowLineVisible = params.isNowLineVisible,
            )
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
        val phase1TotalMs = SystemClock.elapsedRealtime() - phase1StartMs
        fullGraphRefreshJobs[appWidgetId] = asyncScope.launch {
            val appLogDao = WeatherDatabase.getDatabase(appContext).appLogDao()
            appLogDao.log("STARTUP_PHASE2", "widget=$appWidgetId status=scheduled delayMs=$STARTUP_FULL_GRAPH_REFRESH_DELAY_MS phase1TotalMs=${phase1TotalMs}ms")
            try {
                delay(STARTUP_FULL_GRAPH_REFRESH_DELAY_MS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                val totalElapsedMs = SystemClock.elapsedRealtime() - phase1StartMs
                appLogDao.log("STARTUP_PHASE2", "widget=$appWidgetId status=cancelled totalElapsedMs=${totalElapsedMs}ms")
                appContext.sendBroadcast(Intent(appContext, WeatherWidgetProvider::class.java).apply {
                    action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                    putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, "\u26A0\uFE0F Phase2 cancelled (${totalElapsedMs}ms)")
                })
                throw e
            }
            val totalElapsedMs = SystemClock.elapsedRealtime() - phase1StartMs
            appLogDao.log("STARTUP_PHASE2", "widget=$appWidgetId status=fired totalElapsedMs=${totalElapsedMs}ms")
            appContext.sendBroadcast(Intent(appContext, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
            })
        }
    }

    private fun applyCurrentTempHeader(
        views: RemoteViews,
        currentTemp: Float?,
        numColumns: Int,
        widthDp: Int,
        isStaleEstimate: Boolean,
        appliedDelta: Float?,
        isNowLineVisible: Boolean,
    ) {
        if (currentTemp != null) {
            val formattedTemp =
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = currentTemp,
                    numColumns = numColumns,
                    isStaleEstimate = isStaleEstimate,
                )
            views.setTextViewText(R.id.current_temp, formattedTemp)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
            val tempTextSizeSp = if (widthDp < 420) 22f else 26f
            views.setTextViewTextSize(R.id.current_temp, TypedValue.COMPLEX_UNIT_SP, tempTextSizeSp)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
        }

        if (currentTemp != null && isNowLineVisible && appliedDelta != null && kotlin.math.abs(appliedDelta) >= DELTA_VISIBILITY_THRESHOLD) {
            val deltaText = String.format("%+.1f", appliedDelta)
            val deltaColor = Color.parseColor(DELTA_COLOR_HEX)
            views.setTextViewText(R.id.current_temp_delta, deltaText)
            views.setTextColor(R.id.current_temp_delta, deltaColor)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }
    }

    private fun getCurrentHourForecast(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): HourlyForecastEntity? {
        val currentHourKey = WeatherTimeUtils.toHourlyForecastKeyMs(LocalDateTime.now())

        return hourlyForecasts
            .filter { it.dateTime == currentHourKey }
            .let { forecasts ->
                forecasts.find { it.source == displaySource.id }
                    ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: forecasts.firstOrNull()
            }
    }

    private fun setupCurrentTempToggle(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        HeaderTapTargetHelper.bindToggleTemperatureHeader(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            interactionSource = "current_temp_header",
        )
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)
    }
}
