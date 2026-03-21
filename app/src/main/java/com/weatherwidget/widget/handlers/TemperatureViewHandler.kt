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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SpatialInterpolator
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolution
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Handler for the temperature view mode.
 */
object TemperatureViewHandler {
    private const val TAG = "TemperatureViewHandler"
    private const val CELL_HEIGHT_DP = 90
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private const val MAX_PERSISTED_BLEND_DEBUG_LINES = 12
    private const val HOURLY_BACKFILL_COOLDOWN_MS = 30 * 60 * 1000L
    private const val HOURLY_BACKFILL_SOURCE_KEY = "NWS_HOURLY_HISTORY"
    private const val CURRENT_TEMP_FOLLOW_UP_EPSILON = 0.05f
    private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 900L
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refinementTokens = ConcurrentHashMap<Int, Long>()
    private val fullGraphRefreshTokens = ConcurrentHashMap<Int, Long>()

    // Intent actions
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    private const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"
    private const val ACTION_SHOW_OBSERVATIONS = "com.weatherwidget.ACTION_SHOW_OBSERVATIONS"

    internal data class SelectedObservationSeries(
        val stationId: String?,
        val stationName: String?,
        val stationType: String?,
        val observations: List<com.weatherwidget.data.local.ObservationEntity>,
        val rejectedGroupCount: Int,
    )

    private data class StationTimeSeriesPoint(
        val timestamp: Long,
        val temperature: Float,
        val stationId: String,
        val stationName: String,
        val distanceKm: Float,
        val stationType: String,
        val sourceKind: String,
    )

    @androidx.annotation.VisibleForTesting
    internal data class HourlyBackfillDecision(
        val shouldRequest: Boolean,
        val reason: String,
    )

    /**
     * Update widget with hourly temperature data.
     */
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        precipProbability: Int? = null,
        observedCurrentTemp: Float? = null,
        observedCurrentTempFetchedAt: Long? = null,
        onFetchDotResolved: ((TemperatureGraphRenderer.FetchDotDebug) -> Unit)? = null,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
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

        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

        // Hourly mode: hide graph day zones
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)

        // Set up zoom tap zones
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        // Setup navigation buttons
        setupNavigationButtons(context, views, appWidgetId, stateManager)

        // Setup current temp click to toggle view
        setupCurrentTempToggle(context, views, appWidgetId)
        setupHomeShortcut(context, views, appWidgetId)
        setupSettingsShortcut(context, views, appWidgetId)

        // Get current display source
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val dayName = centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val sourceIndicator = if (centerTime.toLocalDate() == LocalDateTime.now().toLocalDate()) {
            displaySource.shortDisplayName
        } else {
            "$dayName • ${displaySource.shortDisplayName}"
        }
        views.setTextViewText(R.id.api_source, sourceIndicator)

        // Set weather icon - use hourly forecast condition for current hour
        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val isNight = SunPositionUtils.isNight(now, lat, lon)

        // Get current hour's condition from hourly forecasts
        val currentHourForecast = getCurrentHourForecast(hourlyForecasts, displaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = isNight,
            cloudCover = currentHourForecast?.cloudCover,
        )
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        // Weather icon + bottom graph zone → cloud cover view
        val goCloudIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetIntentRouter.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.CLOUD_COVER.name)
        }
        val goCloudPending = PendingIntent.getBroadcast(
            context, appWidgetId * 100 + 900, goCloudIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, goCloudPending)
        views.setViewVisibility(R.id.graph_bottom_zone, View.VISIBLE)
        views.setOnClickPendingIntent(R.id.graph_bottom_zone, goCloudPending)

        // Setup API toggle
        setupApiToggle(context, views, appWidgetId, numRows)

        // Setup History shortcut
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource)

        // Setup Current Stations shortcut
        setupCurrentStationsShortcut(context, views, appWidgetId)

        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        val deferStartupGraphActuals = startupToken != null && useGraph
        var obsQueryMs = 0L
        var buildHourDataMs = 0L
        var renderMs = 0L

        var graphObservedTemp: Float? = null
        var graphObservedAt: Long? = null

        val graphHours =
            if (useGraph) {
                // Query actuals for the graph's time window (WIDE: backHours=8)
                val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
                val graphStart = alignedCenter.minusHours(zoom.backHours)
                val graphEnd = alignedCenter.plusHours(zoom.forwardHours)
                val database = WeatherDatabase.getDatabase(context)
                val observations =
                    if (deferStartupGraphActuals) {
                        Log.d(TAG, "updateWidget: widget=$appWidgetId startup graph fast path, skipping actual observation query")
                        emptyList()
                    } else {
                        val minEpoch = graphStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val maxEpoch = graphEnd.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val obsStartMs = SystemClock.elapsedRealtime()
                        val loaded = repository?.getObservationsInRange(minEpoch, maxEpoch, lat, lon) ?: emptyList()
                        val afterObsMs = SystemClock.elapsedRealtime()
                        obsQueryMs = afterObsMs - obsStartMs
                        Log.d(TAG, "updateWidget: widget=$appWidgetId observations=${loaded.size}, zoom=$zoom")
                        maybeEnqueueHourlyObservationBackfill(
                            context = context,
                            database = database,
                            stateManager = stateManager,
                            appWidgetId = appWidgetId,
                            displaySource = displaySource,
                            graphStart = graphStart,
                            graphEnd = graphEnd,
                            observations = loaded,
                            repositoryPresent = repository != null,
                        )
                        loaded
                    }
                val buildHourDataStartMs = SystemClock.elapsedRealtime()
                val blendDebugLines = mutableListOf<String>()
                val hourData = buildHourDataList(
                    hourlyForecasts,
                    centerTime,
                    numColumns,
                    displaySource,
                    zoom,
                    observations,
                    onBlendDebug = { line -> blendDebugLines += line },
                )
                val afterBlendMs = SystemClock.elapsedRealtime()
                buildHourDataMs = afterBlendMs - buildHourDataStartMs
                val actualCount = hourData.count { it.isActual }
                Log.d(TAG, "updateWidget: widget=$appWidgetId hours=${hourData.size}, actualHours=$actualCount")

                // Extract the latest ground-truth observation from the graph's blending results.
                // This ensures the header delta and the graph dot use identical values.
                val latestObs = hourData.filter { it.isObservedActual && it.actualTemperature != null }
                    .maxByOrNull { it.dateTime }
                if (latestObs != null) {
                    graphObservedTemp = latestObs.actualTemperature
                    graphObservedAt = latestObs.dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }

                if (!deferStartupGraphActuals) {
                    val stationIds = observations
                        .filter { matchesObservationSource(it, displaySource) }
                        .map { it.stationId }.toSet()
                    database.appLogDao().log(
                        "IDW_BLEND",
                        "source=${displaySource.id} stations=${stationIds.size} [${stationIds.joinToString(",")}] blendedPoints=$actualCount",
                    )
                    blendDebugLines
                        .take(MAX_PERSISTED_BLEND_DEBUG_LINES)
                        .forEach { line ->
                            database.appLogDao().log("TEMP_ACTUALS_DEBUG", line)
                        }
                    if (blendDebugLines.size > MAX_PERSISTED_BLEND_DEBUG_LINES) {
                        database.appLogDao().log(
                            "TEMP_ACTUALS_DEBUG",
                            "omitted=${blendDebugLines.size - MAX_PERSISTED_BLEND_DEBUG_LINES} additional blend debug lines",
                        )
                    }
                    val obsBlendMs = obsQueryMs + buildHourDataMs
                    if (obsBlendMs > 100) {
                        database.appLogDao().log(
                            "TEMP_OBS_SLOW",
                            "widget=$appWidgetId obsQuery=${obsQueryMs}ms blend=${buildHourDataMs}ms total=${obsBlendMs}ms",
                        )
                    }
                } else {
                    database.appLogDao().log(
                        "TEMP_STARTUP_FAST_PATH",
                        "widget=$appWidgetId source=${displaySource.id} startup graph skipped observation blending",
                    )
                }
                hourData
            } else {
                emptyList()
            }

        // Final current temperature resolution using either the graph's fresh blending result
        // or falling back to the simple database observation.
        val storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource)
        val finalObsTemp = observedCurrentTemp ?: graphObservedTemp
        val finalObsAt = observedCurrentTempFetchedAt ?: graphObservedAt

        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution =
            if (deferCurrentTempResolution) {
                val quick =
                    CurrentTemperatureResolver.resolveQuick(
                        now = now,
                        displaySource = displaySource,
                        hourlyForecasts = hourlyForecasts,
                        observedCurrentTemp = finalObsTemp,
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
                    observedCurrentTemp = finalObsTemp,
                    observedCurrentTempFetchedAt = finalObsAt,
                    storedDeltaState = storedDeltaState,
                    currentLat = lat,
                    currentLon = lon,
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

        if (currentTemp != null) {
            val formattedTemp =
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = currentTemp,
                    numColumns = numColumns,
                    isStaleEstimate = currentTempResolution.isStaleEstimate,
                )
            views.setTextViewText(R.id.current_temp, formattedTemp)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)

            // Update delta badge
            if (deltaVisible) {
                val deltaText = String.format("%+.1f", delta)
                val deltaColor = if (delta > 0) Color.parseColor("#FF6B35") else Color.parseColor("#5AC8FA")
                views.setTextViewText(R.id.current_temp_delta, deltaText)
                views.setTextColor(R.id.current_temp_delta, deltaColor)
                views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.current_temp_delta, View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }

        val headerPrecipProbability =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = precipProbability,
                referenceTime = centerTime,
            )

        // Show precipitation probability next to current temp when rain is expected
        if (headerPrecipProbability != null && headerPrecipProbability > 0) {
            views.setTextViewText(R.id.precip_probability, "$headerPrecipProbability%")
            val textSizeSp = HeaderPrecipCalculator.getPrecipTextSize(headerPrecipProbability)
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }

        Log.d(
            TAG,
            buildHeaderStateLog(
                widgetId = appWidgetId,
                viewMode = com.weatherwidget.widget.ViewMode.TEMPERATURE,
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
                precipVisible = headerPrecipProbability != null && headerPrecipProbability > 0,
                precipProbability = headerPrecipProbability,
                isNowLineVisible = isNowLineVisible,
                offset = hourlyOffset,
                zoom = zoom,
                resolveMs = resolveMs,
            ),
        )

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.VISIBLE)

            // Use actual widget dimensions for bitmap
            // Account for 8dp root padding + 4dp graph margins on each side = 24dp total
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

            // Render temperature graph
            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = graphHours,
                widthPx = widthPx,
                heightPx = heightPx,
                currentTime = now,
                bitmapScale = bitmapScale,
                appliedDelta = if (isNowLineVisible) currentTempResolution.appliedDelta else null,
                actualSeriesAnchorAt = graphHours.lastOrNull { it.isObservedActual }?.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                onFetchDotResolved = onFetchDotResolved,
            )
            renderMs = SystemClock.elapsedRealtime() - renderStartMs
            if (renderMs > 100) {
                val database = WeatherDatabase.getDatabase(context)
                database.appLogDao().log(
                    "TEMP_RENDER_SLOW",
                    "widget=$appWidgetId renderMs=${renderMs}ms size=${widthPx}x${heightPx}",
                )
            }
            views.setImageViewBitmap(R.id.graph_view, bitmap)
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)

            // Text mode: show hourly data as text
            updateHourlyTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
        }

        WeatherDatabase.getDatabase(context).appLogDao().log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=TEMPERATURE state=data thread=${Thread.currentThread().name}")
        appWidgetManager.updateAppWidget(appWidgetId, views)
        if (deferCurrentTempResolution) {
            scheduleCurrentTempRefinement(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                stateManager = stateManager,
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                observedCurrentTemp = observedCurrentTemp,
                observedCurrentTempFetchedAt = observedCurrentTempFetchedAt,
                currentLat = lat,
                currentLon = lon,
                numColumns = numColumns,
                isNowLineVisible = isNowLineVisible,
                quickResolution = currentTempResolution,
                storedDeltaState = storedDeltaState,
            )
        }
        if (deferStartupGraphActuals) {
            scheduleStartupFullGraphRefresh(context, appWidgetId)
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

    private fun scheduleCurrentTempRefinement(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        observedCurrentTemp: Float?,
        observedCurrentTempFetchedAt: Long?,
        currentLat: Double,
        currentLon: Double,
        numColumns: Int,
        isNowLineVisible: Boolean,
        quickResolution: CurrentTemperatureResolution,
        storedDeltaState: com.weatherwidget.widget.CurrentTemperatureDeltaState?,
    ) {
        val token = SystemClock.elapsedRealtimeNanos()
        refinementTokens[appWidgetId] = token
        asyncScope.launch {
            val refined =
                CurrentTemperatureResolver.resolve(
                    now = now,
                    displaySource = displaySource,
                    hourlyForecasts = hourlyForecasts,
                    observedCurrentTemp = observedCurrentTemp,
                    observedCurrentTempFetchedAt = observedCurrentTempFetchedAt,
                    storedDeltaState = storedDeltaState,
                    currentLat = currentLat,
                    currentLon = currentLon,
                )
            if (refinementTokens[appWidgetId] != token) return@launch

            if (refined.shouldClearStoredDelta) {
                stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
            }
            refined.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it) }

            if (!shouldApplyRefinedHeaderUpdate(quickResolution, refined, isNowLineVisible)) {
                return@launch
            }

            val partialViews = RemoteViews(context.packageName, R.layout.widget_weather)
            applyCurrentTempHeader(
                views = partialViews,
                currentTemp = refined.displayTemp,
                numColumns = numColumns,
                isStaleEstimate = refined.isStaleEstimate,
                appliedDelta = refined.appliedDelta,
                isNowLineVisible = isNowLineVisible,
            )
            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, partialViews)
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
    ) {
        val token = SystemClock.elapsedRealtimeNanos()
        fullGraphRefreshTokens[appWidgetId] = token
        asyncScope.launch {
            delay(STARTUP_FULL_GRAPH_REFRESH_DELAY_MS)
            if (fullGraphRefreshTokens[appWidgetId] != token) return@launch
            val refreshIntent =
                Intent(context, WeatherWidgetProvider::class.java).apply {
                    action = WeatherWidgetProvider.ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
                }
            context.sendBroadcast(refreshIntent)
        }
    }

    private fun applyCurrentTempHeader(
        views: RemoteViews,
        currentTemp: Float?,
        numColumns: Int,
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
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
        }

        if (currentTemp != null && isNowLineVisible && appliedDelta != null && kotlin.math.abs(appliedDelta) >= DELTA_VISIBILITY_THRESHOLD) {
            val deltaText = String.format("%+.1f", appliedDelta)
            val deltaColor = if (appliedDelta > 0) Color.parseColor("#FF6B35") else Color.parseColor("#5AC8FA")
            views.setTextViewText(R.id.current_temp_delta, deltaText)
            views.setTextColor(R.id.current_temp_delta, deltaColor)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }
    }

    /**
     * Get the weather condition for the current hour from hourly forecasts.
     */
    private fun getCurrentHourForecast(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): HourlyForecastEntity? {
        val currentHourKey = WeatherTimeUtils.toHourlyForecastKey(LocalDateTime.now())

        return hourlyForecasts
            .filter { it.dateTime == currentHourKey }
            .let { forecasts ->
                forecasts.find { it.source == displaySource.id }
                    ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: forecasts.firstOrNull()
            }
    }

    private val HOUR_ZONE_IDS = listOf(
        R.id.graph_hour_zone_0, R.id.graph_hour_zone_1, R.id.graph_hour_zone_2,
        R.id.graph_hour_zone_3, R.id.graph_hour_zone_4, R.id.graph_hour_zone_5,
        R.id.graph_hour_zone_6, R.id.graph_hour_zone_7, R.id.graph_hour_zone_8,
        R.id.graph_hour_zone_9, R.id.graph_hour_zone_10, R.id.graph_hour_zone_11,
    )

    /**
     * WIDE zoom: 12 zones overlay the graph, each encoding the center hour for NARROW zoom.
     * NARROW zoom: 12 zones overlay the graph, each encoding the center hour for WIDE zoom.
     */
    private fun setupZoomTapZones(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        zoom: com.weatherwidget.widget.ZoomLevel,
        hourlyOffset: Int,
    ) {
        // Show hour zones only over the graph body, not the bottom icon/label row.
        views.setViewVisibility(R.id.graph_hour_zones, View.VISIBLE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setOnClickPendingIntent(R.id.graph_view, null)
        views.setOnClickPendingIntent(R.id.graph_body_tap_zone, null)

        HOUR_ZONE_IDS.forEachIndexed { i, zoneId ->
            val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(i, hourlyOffset, zoom)
            val zoomIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_ZOOM
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 100 + 500 + i,
                zoomIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }
    }

    private fun setupNavigationButtons(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
    ) {
        val canLeft = stateManager.canNavigateHourlyLeft(appWidgetId)
        val canRight = stateManager.canNavigateHourlyRight(appWidgetId)

        // Always show the left arrow
        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)

        if (canLeft) {
            val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val leftPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.nav_left, leftPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, leftPendingIntent)
        } else {
            val toastIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, "No additional history available")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val toastPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), toastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.nav_left, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, toastPendingIntent)
        }

        // Always show the right arrow
        views.setViewVisibility(R.id.nav_right, View.VISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)

        if (canRight) {
            val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val rightPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.nav_right, rightPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, rightPendingIntent)
        } else {
            val toastIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, "No more forecast available")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val toastPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), toastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.nav_right, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, toastPendingIntent)
        }
    }

    private fun setupCurrentTempToggle(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val toggleIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WeatherWidgetProvider.EXTRA_INTERACTION_SOURCE, "current_temp_header")
            }
        val togglePendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.viewToggle(appWidgetId),
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)

        val precipIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PRECIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val precipPendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.precipToggle(appWidgetId),
                precipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)
    }

    private fun setupApiToggle(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        numRows: Int,
    ) {
        val toggleIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_API
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val togglePendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.apiToggle(appWidgetId),
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.api_source_container, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.api_touch_zone, togglePendingIntent)

        val textSizeSp =
            when {
                numRows >= 3 -> 18f
                numRows >= 2 -> 16f
                else -> 14f
            }
        views.setTextViewTextSize(R.id.api_source, android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun setupHistoryShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        centerTime: LocalDateTime,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ) {
        val dateStr = centerTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val historyIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", dateStr)
            putExtra("showHistory", true)
            putExtra("isHistory", centerTime.toLocalDate().isBefore(LocalDate.now()))
            putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 100 + 700,
            historyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.history_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.history_touch_zone, pendingIntent)
        views.setViewVisibility(R.id.history_icon, View.VISIBLE)
        views.setViewVisibility(R.id.history_touch_zone, View.VISIBLE)
    }

    private fun setupHomeShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val homeIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetIntentRouter.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.DAILY.name)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.home(appWidgetId),
            homeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.home_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.home_touch_zone, pendingIntent)
        views.setViewVisibility(R.id.home_icon, View.VISIBLE)
        views.setViewVisibility(R.id.home_touch_zone, View.VISIBLE)
    }

    private fun setupCurrentStationsShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val obsIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_SHOW_OBSERVATIONS
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 100 + 800,
            obsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.current_stations_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.current_stations_touch_zone, pendingIntent)
        views.setViewVisibility(R.id.current_stations_icon, View.VISIBLE)
        views.setViewVisibility(R.id.current_stations_touch_zone, View.VISIBLE)
    }

    private fun setupSettingsShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent =
            PendingIntent.getActivity(
                context,
                WidgetRequestCodes.settings(appWidgetId),
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.settings_touch_zone, settingsPendingIntent)
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
        actuals: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
        onBlendDebug: ((String) -> Unit)? = null,
    ): List<TemperatureGraphRenderer.HourData> {
        val hours = mutableListOf<TemperatureGraphRenderer.HourData>()
        val now = LocalDateTime.now()

        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == displaySource.id }
                    val gap = entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    val fallback = entry.value.firstOrNull()
                    preferred ?: gap ?: fallback
                }

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val sourceActuals = actuals.filter { matchesObservationSource(it, displaySource) }
        val stationCount = sourceActuals.map { it.stationId }.toSet().size
        if (sourceActuals.isNotEmpty()) {
            val stationBreakdown = sourceActuals
                .groupBy { it.stationId }
                .entries
                .sortedBy { it.key }
                .joinToString("; ") { (stationId, rows) ->
                    val minTime = java.time.Instant.ofEpochMilli(rows.minOf { it.timestamp })
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    val maxTime = java.time.Instant.ofEpochMilli(rows.maxOf { it.timestamp })
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    "$stationId rows=${rows.size} span=$minTime-$maxTime"
                }
            val summary =
                "window source=${displaySource.id} start=$startHour end=$endHour sourceRows=${sourceActuals.size} stations=$stationCount breakdown=[$stationBreakdown]"
            Log.d("IDW_BLEND", summary)
            onBlendDebug?.invoke(summary)
        } else {
            onBlendDebug?.invoke("window source=${displaySource.id} start=$startHour end=$endHour sourceRows=0 stations=0")
        }
        val blendedActuals = blendObservationSeries(
            observations = actuals,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            userLat = lat,
            userLon = lon,
            startHour = startHour,
            endHour = endHour,
            onBlendDebug = onBlendDebug,
        )
        Log.d(
            TAG,
            "buildHourDataList: source=${displaySource.id}, IDW blend from $stationCount stations, " +
                "blendedPoints=${blendedActuals.size}"
        )

        val labelInterval = zoom.labelInterval

        var currentHour = startHour
        var hourIndex = 0

        // 1. Collect top-of-hour forecasts
        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val forecast = forecastsByTime[hourKey]

            if (forecast != null) {
                val isCurrentHour = currentHour == now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                val showLabel =
                    when (zoom) {
                        com.weatherwidget.widget.ZoomLevel.WIDE -> hourIndex % labelInterval == 0
                        com.weatherwidget.widget.ZoomLevel.NARROW -> true
                    }

                val isNight = SunPositionUtils.isNight(currentHour, lat, lon)
                val iconRes = WeatherIconMapper.getIconResource(
                    condition = forecast.condition,
                    isNight = isNight,
                    cloudCover = forecast.cloudCover,
                )
                val isSunny = iconRes == R.drawable.ic_weather_clear || iconRes == R.drawable.ic_weather_mostly_clear || iconRes == R.drawable.ic_weather_night
                val isRainy = iconRes == R.drawable.ic_weather_rain || iconRes == R.drawable.ic_weather_storm || iconRes == R.drawable.ic_weather_snow
                val isMixed = iconRes == R.drawable.ic_weather_mostly_cloudy || iconRes == R.drawable.ic_weather_mostly_cloudy_night || iconRes == R.drawable.ic_weather_partly_cloudy || iconRes == R.drawable.ic_weather_partly_cloudy_night || iconRes == R.drawable.ic_weather_fog_cloudy

                hours.add(
                    TemperatureGraphRenderer.HourData(
                        dateTime = currentHour,
                        temperature = forecast.temperature,
                        label = formatHourLabel(currentHour),
                        iconRes = iconRes,
                        isNight = isNight,
                        isSunny = isSunny,
                        isRainy = isRainy,
                        isMixed = isMixed,
                        isCurrentHour = isCurrentHour,
                        showLabel = showLabel,
                        isActual = false,
                        actualTemperature = null,
                        isObservedActual = false,
                    ),
                )
                hourIndex++
            }
            currentHour = currentHour.plusHours(1)
        }

        // 2. Inject sub-hourly actuals
        val finalHours = mutableListOf<TemperatureGraphRenderer.HourData>()
        val allTimes = hours.map { it.dateTime }.toMutableSet()
        val actualMap = mutableMapOf<LocalDateTime, com.weatherwidget.data.local.ObservationEntity>()

        blendedActuals.forEach { obs ->
            val obsTime = java.time.Instant.ofEpochMilli(obs.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            
            if (!obsTime.isBefore(startHour) && !obsTime.isAfter(endHour) && obsTime.isBefore(now)) {
                allTimes.add(obsTime)
                actualMap[obsTime] = obs
            }
        }

        val sortedTimes = allTimes.sorted()

        for (time in sortedTimes) {
            val isTopHour = time.minute == 0 && time.second == 0
            val isPast = time.isBefore(now)
            val actualObservation = actualMap[time]
            val actualTemp = actualObservation?.temperature
            val isRawObservedActual = actualObservation?.condition == "observed"

            if (isTopHour) {
                val topHourData = hours.find { it.dateTime == time }
                if (topHourData != null) {
                    finalHours.add(
                        topHourData.copy(
                            isActual = isPast && actualTemp != null,
                            actualTemperature = actualTemp,
                            isObservedActual = isPast && isRawObservedActual,
                        )
                    )
                }
            } else {
                val prevTopHour = hours.lastOrNull { !it.dateTime.isAfter(time) }
                val nextTopHour = hours.firstOrNull { it.dateTime.isAfter(time) }
                
                val forecastTemp = if (prevTopHour != null && nextTopHour != null) {
                    val totalSecs = java.time.Duration.between(prevTopHour.dateTime, nextTopHour.dateTime).seconds
                    val elapsedSecs = java.time.Duration.between(prevTopHour.dateTime, time).seconds
                    val fraction = elapsedSecs.toFloat() / totalSecs.toFloat()
                    prevTopHour.temperature + (nextTopHour.temperature - prevTopHour.temperature) * fraction
                } else {
                    prevTopHour?.temperature ?: nextTopHour?.temperature ?: 0f
                }

                finalHours.add(
                    TemperatureGraphRenderer.HourData(
                        dateTime = time,
                        temperature = forecastTemp,
                        label = formatHourLabel(time),
                        iconRes = null,
                        isNight = SunPositionUtils.isNight(time, lat, lon),
                        isSunny = false,
                        isRainy = false,
                        isMixed = false,
                        isCurrentHour = false,
                        showLabel = false,
                        isActual = true,
                        actualTemperature = actualTemp,
                        isObservedActual = isRawObservedActual,
                    )
                )
            }
        }

        var lastActual: Float? = null
        for (i in finalHours.indices) {
            if (finalHours[i].isActual && finalHours[i].actualTemperature != null) {
                lastActual = finalHours[i].actualTemperature
            } else if (finalHours[i].dateTime.isBefore(now)) {
                if (lastActual != null) {
                    finalHours[i] =
                        finalHours[i].copy(
                            isActual = true,
                            actualTemperature = lastActual,
                            isObservedActual = false,
                        )
                } else {
                    finalHours[i] =
                        finalHours[i].copy(
                            isActual = false,
                            actualTemperature = null,
                            isObservedActual = false,
                        )
                }
            }
        }

        return finalHours
    }

    @androidx.annotation.VisibleForTesting
    internal fun evaluateHourlyBackfillNeed(
        displaySource: WeatherSource,
        graphStart: LocalDateTime,
        graphEnd: LocalDateTime,
        observations: List<com.weatherwidget.data.local.ObservationEntity>,
        now: LocalDateTime = LocalDateTime.now(),
    ): HourlyBackfillDecision {
        if (displaySource != WeatherSource.NWS) {
            return HourlyBackfillDecision(false, "non_nws_source")
        }

        val sourceObservations = observations.filter { matchesObservationSource(it, displaySource) }
        if (sourceObservations.isEmpty()) {
            return HourlyBackfillDecision(true, "no_nws_observations")
        }

        val sourceWindowEnd = minOf(graphEnd, now)
        val sourceWindowEndMs = sourceWindowEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sortedTimestamps = sourceObservations.map { it.timestamp }.sorted()
        val latestGapMin = ((sourceWindowEndMs - sortedTimestamps.last()).coerceAtLeast(0L) / 60_000L)
        val maxGapMin = sortedTimestamps.zipWithNext { a, b -> (b - a) / 60_000L }.maxOrNull() ?: 0L
        val singletonStations =
            sourceObservations.groupBy { it.stationId }
                .filterValues { rows -> rows.size <= 1 }
                .keys

        return when {
            singletonStations.isNotEmpty() ->
                HourlyBackfillDecision(true, "singleton_stations=${singletonStations.sorted().joinToString(",")}")
            latestGapMin > 45L ->
                HourlyBackfillDecision(true, "latest_gap_min=$latestGapMin")
            maxGapMin > 75L ->
                HourlyBackfillDecision(true, "max_gap_min=$maxGapMin")
            else ->
                HourlyBackfillDecision(false, "coverage_ok latest_gap_min=$latestGapMin max_gap_min=$maxGapMin")
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun selectObservationSeries(
        observations: List<com.weatherwidget.data.local.ObservationEntity>,
        displaySource: WeatherSource,
        startHour: LocalDateTime,
        endHour: LocalDateTime,
    ): SelectedObservationSeries {
        val sourceObservations = observations.filter { matchesObservationSource(it, displaySource) }
        if (sourceObservations.isEmpty()) {
            return SelectedObservationSeries(
                stationId = null,
                stationName = null,
                stationType = null,
                observations = emptyList(),
                rejectedGroupCount = 0,
            )
        }

        val grouped = sourceObservations.groupBy { it.stationId }
        val selectedEntry = grouped.entries.maxWithOrNull(
            compareBy<Map.Entry<String, List<com.weatherwidget.data.local.ObservationEntity>>>(
                { entry -> entry.value.map { observationHour(it) }.toSet().size },
                { entry -> entry.value.size },
                { entry -> -entry.value.minOfOrNull { it.distanceKm }!! },
                { entry -> entry.value.maxOf { it.timestamp } },
                { entry -> -entry.key.hashCode() },
            )
        )

        val chosen = selectedEntry?.value.orEmpty().sortedBy { it.timestamp }
        val metadata = chosen.firstOrNull()
        return SelectedObservationSeries(
            stationId = selectedEntry?.key,
            stationName = metadata?.stationName,
            stationType = metadata?.stationType,
            observations = chosen.filter { obs ->
                val obsTime = java.time.Instant.ofEpochMilli(obs.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
                !obsTime.isBefore(startHour) && !obsTime.isAfter(endHour)
            },
            rejectedGroupCount = (grouped.size - 1).coerceAtLeast(0),
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun blendObservationSeries(
        observations: List<com.weatherwidget.data.local.ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        userLat: Double,
        userLon: Double,
        startHour: LocalDateTime,
        endHour: LocalDateTime,
        onBlendDebug: ((String) -> Unit)? = null,
    ): List<com.weatherwidget.data.local.ObservationEntity> {
        val zoneId = java.time.ZoneId.systemDefault()
        val startMs = startHour.atZone(zoneId).toInstant().toEpochMilli()
        val endMs = endHour.atZone(zoneId).toInstant().toEpochMilli()

        val filtered = observations
            .filter { matchesObservationSource(it, displaySource) }
            .filter { it.timestamp in startMs..endMs }
            .sortedBy { it.timestamp }

        if (filtered.isEmpty()) return emptyList()

        val windowMs = 15 * 60 * 1000L
        val maxStationInterpolationGapMs = 60 * 60 * 1000L
        val maxStationExtrapolationGapMs = 60 * 60 * 1000L
        val dedupMs = 5 * 60 * 1000L
        val stationSeries = buildStationTimeSeries(
            observations = filtered,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            interpolationStepMs = windowMs,
            maxInterpolationGapMs = maxStationInterpolationGapMs,
            maxExtrapolationGapMs = maxStationExtrapolationGapMs,
            endMs = endMs,
            onBlendDebug = onBlendDebug,
        )
        val candidateTimes = stationSeries
            .values
            .flatten()
            .map { it.timestamp }
            .distinct()
            .sorted()
        val result = mutableListOf<com.weatherwidget.data.local.ObservationEntity>()
        var lastEmittedMs = 0L
        var previousCohortStations: Set<String>? = null

        for (targetTs in candidateTimes) {
            if (targetTs - lastEmittedMs < dedupMs) {
                continue
            }
            val peers = stationSeries.values.mapNotNull { points ->
                resolveStationPointForTimestamp(points, targetTs, windowMs)
            }
            if (peers.isEmpty()) continue

            val anchor = peers.minByOrNull { kotlin.math.abs(it.timestamp - targetTs) } ?: continue
            val blendedTemp = if (peers.size == 1) {
                peers.first().temperature
            } else {
                val peerEntities = peers.map { point ->
                    com.weatherwidget.data.local.ObservationEntity(
                        stationId = point.stationId,
                        stationName = point.stationName,
                        timestamp = point.timestamp,
                        temperature = point.temperature,
                        condition = point.sourceKind,
                        locationLat = userLat,
                        locationLon = userLon,
                        distanceKm = point.distanceKm,
                        stationType = point.stationType,
                        api = displaySource.id,
                    )
                }
                SpatialInterpolator.interpolateIDW(userLat, userLon, peerEntities, targetTs)
                    ?: anchor.temperature
            }
            val timeStr = java.time.Instant.ofEpochMilli(targetTs)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val cohortStations = peers.map { it.stationId }.toSortedSet()
            val debugLine =
                if (peers.size == 1) {
                    val point = peers.first()
                    "emit t=$timeStr single_station=${point.stationId} temp=${String.format("%.1f", point.temperature)} distanceKm=${String.format("%.1f", point.distanceKm)} blended=${String.format("%.1f", blendedTemp)} source=${point.sourceKind}"
                } else {
                    val weightSum = peers.sumOf { 1.0 / (it.distanceKm * it.distanceKm) }
                    val peerStr = peers.joinToString(",") { p ->
                        val w = (1.0 / (p.distanceKm * p.distanceKm)) / weightSum
                        "${p.stationId}:${String.format("%.1f", p.temperature)}F@${String.format("%.1f", p.distanceKm)}km(w=${String.format("%.2f", w)},${p.sourceKind})"
                    }
                    val cohortChanged = previousCohortStations != cohortStations
                    "emit t=$timeStr blended=${String.format("%.1f", blendedTemp)} stations=[${peerStr}] cohortChanged=$cohortChanged"
                }
            Log.d("IDW_BLEND", debugLine)
            onBlendDebug?.invoke(debugLine)
            previousCohortStations = cohortStations

            result.add(
                com.weatherwidget.data.local.ObservationEntity(
                    stationId = anchor.stationId,
                    stationName = anchor.stationName,
                    timestamp = targetTs,
                    temperature = blendedTemp,
                    condition = anchor.sourceKind,
                    locationLat = userLat,
                    locationLon = userLon,
                    distanceKm = anchor.distanceKm,
                    stationType = anchor.stationType,
                    api = displaySource.id,
                ),
            )
            lastEmittedMs = targetTs
        }

        return result
    }

    private fun buildStationTimeSeries(
        observations: List<com.weatherwidget.data.local.ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        interpolationStepMs: Long,
        maxInterpolationGapMs: Long,
        maxExtrapolationGapMs: Long,
        endMs: Long,
        onBlendDebug: ((String) -> Unit)?,
    ): Map<String, List<StationTimeSeriesPoint>> =
        observations
            .groupBy { it.stationId }
            .mapValues { (stationId, rows) ->
                val forecastSeries = hourlyForecastSeries(hourlyForecasts, displaySource)
                val allowForecastExtrapolation = displaySource == WeatherSource.NWS
                val sorted = rows.sortedBy { it.timestamp }
                val points = mutableListOf<StationTimeSeriesPoint>()
                for (index in sorted.indices) {
                    val current = sorted[index]
                    points += StationTimeSeriesPoint(
                        timestamp = current.timestamp,
                        temperature = current.temperature,
                        stationId = stationId,
                        stationName = current.stationName,
                        distanceKm = current.distanceKm,
                        stationType = current.stationType,
                        sourceKind = "observed",
                    )

                    if (index == sorted.lastIndex) continue
                    val next = sorted[index + 1]
                    val gapMs = next.timestamp - current.timestamp
                    if (gapMs <= interpolationStepMs) continue

                    if (gapMs <= maxInterpolationGapMs) {
                        var interpolatedTimestamp = current.timestamp + interpolationStepMs
                        while (interpolatedTimestamp < next.timestamp) {
                            val fraction = (interpolatedTimestamp - current.timestamp).toFloat() / gapMs.toFloat()
                            val interpolated = current.temperature + (next.temperature - current.temperature) * fraction
                            val debugLine =
                                "station_interpolate station=$stationId at=${
                                    java.time.Instant.ofEpochMilli(interpolatedTimestamp)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                } temp=${String.format("%.1f", interpolated)} from=${
                                    java.time.Instant.ofEpochMilli(current.timestamp)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                }..${
                                    java.time.Instant.ofEpochMilli(next.timestamp)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                }"
                            Log.d("IDW_BLEND", debugLine)
                            onBlendDebug?.invoke(debugLine)
                            points += StationTimeSeriesPoint(
                                timestamp = interpolatedTimestamp,
                                temperature = interpolated,
                                stationId = stationId,
                                stationName = current.stationName,
                                distanceKm = current.distanceKm,
                                stationType = current.stationType,
                                sourceKind = "interpolated",
                            )
                            interpolatedTimestamp += interpolationStepMs
                        }
                    } else {
                        val debugLine =
                            "station_gap station=$stationId gapMin=${gapMs / 60000} from=${
                                java.time.Instant.ofEpochMilli(current.timestamp)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDateTime()
                                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                            }..${
                                java.time.Instant.ofEpochMilli(next.timestamp)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDateTime()
                                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                            }"
                        Log.d("IDW_BLEND", debugLine)
                        onBlendDebug?.invoke(debugLine)
                    }
                }
                val last = sorted.lastOrNull()
                if (allowForecastExtrapolation && last != null) {
                    addForecastGuidedExtrapolatedPoints(
                        stationId = stationId,
                        lastObservation = last,
                        forecastSeries = forecastSeries,
                        interpolationStepMs = interpolationStepMs,
                        maxExtrapolationGapMs = maxExtrapolationGapMs,
                        endMs = endMs,
                        points = points,
                        onBlendDebug = onBlendDebug,
                    )
                }
                points.sortedBy { it.timestamp }
            }

    private fun addForecastGuidedExtrapolatedPoints(
        stationId: String,
        lastObservation: com.weatherwidget.data.local.ObservationEntity,
        forecastSeries: List<HourlyForecastEntity>,
        interpolationStepMs: Long,
        maxExtrapolationGapMs: Long,
        endMs: Long,
        points: MutableList<StationTimeSeriesPoint>,
        onBlendDebug: ((String) -> Unit)?,
    ) {
        val baseForecastTemp = forecastTemperatureAt(forecastSeries, lastObservation.timestamp) ?: return
        val maxTimestamp = minOf(lastObservation.timestamp + maxExtrapolationGapMs, endMs)
        var extrapolatedTimestamp = lastObservation.timestamp + interpolationStepMs
        while (extrapolatedTimestamp <= maxTimestamp) {
            val targetForecastTemp = forecastTemperatureAt(forecastSeries, extrapolatedTimestamp) ?: break
            val extrapolated = lastObservation.temperature + (targetForecastTemp - baseForecastTemp)
            val debugLine =
                "station_extrapolate station=$stationId at=${
                    java.time.Instant.ofEpochMilli(extrapolatedTimestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                } temp=${String.format("%.1f", extrapolated)} fromObs=${
                    java.time.Instant.ofEpochMilli(lastObservation.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                } forecastDelta=${String.format("%.1f", targetForecastTemp - baseForecastTemp)}"
            Log.d("IDW_BLEND", debugLine)
            onBlendDebug?.invoke(debugLine)
            points += StationTimeSeriesPoint(
                timestamp = extrapolatedTimestamp,
                temperature = extrapolated,
                stationId = stationId,
                stationName = lastObservation.stationName,
                distanceKm = lastObservation.distanceKm,
                stationType = lastObservation.stationType,
                sourceKind = "forecast_extrapolated",
            )
            extrapolatedTimestamp += interpolationStepMs
        }
    }

    private fun hourlyForecastSeries(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): List<HourlyForecastEntity> =
        hourlyForecasts
            .groupBy { it.dateTime }
            .mapNotNull { (_, rows) ->
                val preferred = rows.find { it.source == displaySource.id }
                val gap = rows.find { it.source == WeatherSource.GENERIC_GAP.id }
                val fallback = rows.firstOrNull()
                preferred ?: gap ?: fallback
            }
            .sortedBy { it.dateTime }

    private fun forecastTemperatureAt(
        forecastSeries: List<HourlyForecastEntity>,
        targetTimestamp: Long,
    ): Float? {
        if (forecastSeries.isEmpty()) return null
        val zoneId = java.time.ZoneId.systemDefault()
        val targetTime = java.time.Instant.ofEpochMilli(targetTimestamp).atZone(zoneId).toLocalDateTime()
        val exact = forecastSeries.find { forecastDateTime(it) == targetTime }
        if (exact != null) return exact.temperature

        val before = forecastSeries.lastOrNull { forecastDateTime(it)?.let { dateTime -> !dateTime.isAfter(targetTime) } == true }
        val after = forecastSeries.firstOrNull { forecastDateTime(it)?.let { dateTime -> !dateTime.isBefore(targetTime) } == true }
        if (before == null || after == null) return null
        val beforeDateTime = forecastDateTime(before) ?: return null
        val afterDateTime = forecastDateTime(after) ?: return null
        if (beforeDateTime == afterDateTime) return before.temperature

        val totalSeconds = java.time.Duration.between(beforeDateTime, afterDateTime).seconds
        if (totalSeconds <= 0) return null
        val elapsedSeconds = java.time.Duration.between(beforeDateTime, targetTime).seconds
        val fraction = elapsedSeconds.toFloat() / totalSeconds.toFloat()
        return before.temperature + (after.temperature - before.temperature) * fraction
    }

    private fun forecastDateTime(forecast: HourlyForecastEntity): LocalDateTime? =
        runCatching { LocalDateTime.parse(forecast.dateTime) }.getOrNull()

    private suspend fun maybeEnqueueHourlyObservationBackfill(
        context: Context,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        graphStart: LocalDateTime,
        graphEnd: LocalDateTime,
        observations: List<com.weatherwidget.data.local.ObservationEntity>,
        repositoryPresent: Boolean,
    ) {
        if (!repositoryPresent) return

        val decision = evaluateHourlyBackfillNeed(displaySource, graphStart, graphEnd, observations)
        if (!decision.shouldRequest) {
            database.appLogDao().log(
                "OBS_HOURLY_BACKFILL_SKIP",
                "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason}",
                "INFO",
            )
            return
        }

        if (!stateManager.shouldRefreshMissingActuals(appWidgetId, HOURLY_BACKFILL_SOURCE_KEY, HOURLY_BACKFILL_COOLDOWN_MS)) {
            database.appLogDao().log(
                "OBS_HOURLY_BACKFILL_SKIP",
                "widget=$appWidgetId source=${displaySource.id} reason=cooldown ${decision.reason}",
                "INFO",
            )
            return
        }

        val lat = observations.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = observations.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, lat)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, lon)
                        .putLong(
                            WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS,
                            WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS,
                        )
                        .putString(
                            WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON,
                            "temperature_graph_sparse_history widget=$appWidgetId reason=${decision.reason}",
                        )
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WeatherWidgetProvider.WORK_NAME_OBSERVATION_BACKFILL,
            ExistingWorkPolicy.KEEP,
            request,
        )
        stateManager.markMissingActualsRefreshRequested(appWidgetId, HOURLY_BACKFILL_SOURCE_KEY)
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_REQ",
            "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason} graphStart=$graphStart graphEnd=$graphEnd",
            "INFO",
        )
    }

    private fun resolveStationPointForTimestamp(
        points: List<StationTimeSeriesPoint>,
        targetTs: Long,
        windowMs: Long,
    ): StationTimeSeriesPoint? {
        val exactOrNearest = points.minByOrNull { kotlin.math.abs(it.timestamp - targetTs) } ?: return null
        return if (kotlin.math.abs(exactOrNearest.timestamp - targetTs) <= windowMs) exactOrNearest else null
    }

    private fun matchesObservationSource(
        observation: com.weatherwidget.data.local.ObservationEntity,
        displaySource: WeatherSource,
    ): Boolean =
        when (displaySource) {
            WeatherSource.OPEN_METEO -> observation.stationId.startsWith("OPEN_METEO")
            WeatherSource.WEATHER_API -> observation.stationId.startsWith("WEATHER_API")
            WeatherSource.SILURIAN -> observation.stationId.startsWith("SILURIAN")
            WeatherSource.NWS -> !observation.stationId.startsWith("OPEN_METEO") &&
                !observation.stationId.startsWith("WEATHER_API") &&
                !observation.stationId.startsWith("SILURIAN")
            else -> true
        }

    private fun observationHour(observation: com.weatherwidget.data.local.ObservationEntity): LocalDateTime =
        java.time.Instant.ofEpochMilli(observation.timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)

    private fun formatHourLabel(time: LocalDateTime): String {
        val hour = time.hour
        return when {
            hour == 0 -> "12a"
            hour < 12 -> "${hour}a"
            hour == 12 -> "12p"
            else -> "${hour - 12}p"
        }
    }

    private fun updateHourlyTextMode(
        views: RemoteViews,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
    ) {
        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == displaySource.id }
                        ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                        ?: entry.value.firstOrNull()
                }

        val timeOffsets =
            when {
                numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
                numColumns == 5 -> listOf(0, 3, 6, 9, 12)
                numColumns == 4 -> listOf(0, 3, 6, 9)
                numColumns == 3 -> listOf(0, 3, 6)
                numColumns == 2 -> listOf(0, 6)
                else -> listOf(0)
            }

        val containerIds =
            listOf(
                R.id.day1_container to Quad(R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low),
                R.id.day2_container to Quad(R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low),
                R.id.day3_container to Quad(R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low),
                R.id.day4_container to Quad(R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low),
                R.id.day5_container to Quad(R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low),
                R.id.day6_container to Quad(R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low),
            )

        containerIds.forEachIndexed { index, (containerId, ids) ->
            if (index < timeOffsets.size) {
                val offset = timeOffsets[index]
                val targetTime = centerTime.plusHours(offset.toLong())
                val hourKey = targetTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val forecast = forecastsByTime[hourKey]

                views.setViewVisibility(containerId, View.VISIBLE)

                val label = if (offset == 0) "Now" else "+${offset}h"
                views.setTextViewText(ids.first, label)
                views.setViewVisibility(ids.second, View.GONE)

                if (forecast != null) {
                    val temp = String.format("%.1f°", forecast.temperature)
                    views.setTextViewText(ids.third, temp)
                    views.setTextViewText(ids.fourth, "")
                } else {
                    views.setTextViewText(ids.third, "--°")
                    views.setTextViewText(ids.fourth, "")
                }
            } else {
                views.setViewVisibility(containerId, View.GONE)
            }
        }
    }

    private fun temperatureDeltaHiddenReason(
        currentTemp: Float?,
        appliedDelta: Float?,
        isNowLineVisible: Boolean,
    ): String? =
        when {
            currentTemp == null -> "current_temp_missing"
            !isNowLineVisible -> "now_line_hidden"
            appliedDelta == null -> "no_delta"
            kotlin.math.abs(appliedDelta) < DELTA_VISIBILITY_THRESHOLD -> "below_threshold"
            else -> null
        }

    private fun buildHeaderStateLog(
        widgetId: Int,
        viewMode: com.weatherwidget.widget.ViewMode,
        displaySource: WeatherSource,
        configuredLocation: Pair<Double, Double>?,
        dataLat: Double,
        dataLon: Double,
        dimensions: WidgetDimensions,
        currentTemp: Float?,
        estimatedTemp: Float?,
        observedTemp: Float?,
        appliedDelta: Float?,
        deltaVisible: Boolean,
        deltaHiddenReason: String?,
        precipVisible: Boolean,
        precipProbability: Int?,
        isNowLineVisible: Boolean?,
        offset: Int,
        zoom: ZoomLevel?,
        resolveMs: Long,
    ): String =
        "headerState widget=$widgetId mode=${viewMode.name} source=${displaySource.id} " +
            "configuredLoc=${formatLocation(configuredLocation)} dataLoc=${formatLocation(dataLat to dataLon)} " +
            "cols=${dimensions.cols} rows=${dimensions.rows} sizeDp=${dimensions.widthDp}x${dimensions.heightDp} " +
            "currentTemp=${formatTemp(currentTemp)} estimatedTemp=${formatTemp(estimatedTemp)} " +
            "observedTemp=${formatTemp(observedTemp)} appliedDelta=${formatTemp(appliedDelta)} " +
            "deltaVisible=$deltaVisible deltaHiddenReason=${deltaHiddenReason ?: "none"} " +
            "precipVisible=$precipVisible precipProbability=${precipProbability ?: "none"} " +
            "isNowLineVisible=${isNowLineVisible ?: "n/a"} offset=$offset zoom=${zoom?.name ?: "n/a"} resolveMs=$resolveMs"

    private fun formatLocation(location: Pair<Double, Double>?): String {
        if (location == null) return "none"
        return String.format("%.5f,%.5f", location.first, location.second)
    }

    private fun formatTemp(value: Float?): String = value?.let { String.format("%.2f", it) } ?: "none"

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
