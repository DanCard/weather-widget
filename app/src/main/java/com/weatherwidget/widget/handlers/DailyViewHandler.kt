/**
 * Handler for the daily forecast view mode.
 */
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
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

object DailyViewHandler : WidgetViewHandler {
    private const val TAG = "DailyViewHandler"
    private const val CELL_HEIGHT_DP = 90
    private const val MISSING_ACTUALS_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MISSING_TODAY_SNAPSHOT_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private data class DayIds(
        val container: Int,
        val label: Int,
        val icon: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
    )

    // Intent actions from WeatherWidgetProvider
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    private const val ACTION_DAY_CLICK = "com.weatherwidget.ACTION_DAY_CLICK"
    private const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"
    private const val EXTRA_HOURLY_OFFSET = "com.weatherwidget.EXTRA_HOURLY_OFFSET"

    override fun canHandle(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
    ): Boolean {
        return stateManager.getViewMode(appWidgetId) == com.weatherwidget.widget.ViewMode.DAILY
    }

    override suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<String, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity>,
        dailyActualsBySource: DailyActualsBySource,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
    ) {
        updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherList = weatherList,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActualsBySource = dailyActualsBySource,
            repository = repository,
            now = LocalDateTime.now(),
        )
    }

    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<String, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity>,
        dailyActualsBySource: DailyActualsBySource,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
        startupToken: String? = null,
    ) {
        updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherList = weatherList,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActualsBySource = dailyActualsBySource,
            repository = repository,
            now = LocalDateTime.now(),
            startupToken = startupToken,
        )
    }

    @VisibleForTesting
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<String, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity>,
        dailyActualsBySource: DailyActualsBySource,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
        now: LocalDateTime,
        startupToken: String? = null,
    ) {
        Log.d(TAG, "updateWidget: [START] widgetId=$appWidgetId at time=$now")
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        val stateManager = WidgetStateManager(context)
        val dateOffset = stateManager.getDateOffset(appWidgetId)

        val isEveningMode = NavigationUtils.isEveningMode(now.toLocalTime())
        
        // Single source of truth for time in this update cycle
        val today = now.toLocalDate()
        val skipHistory = NavigationUtils.shouldSkipHistory(isEveningMode, dateOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, dateOffset, isEveningMode)

        // Setup common click actions
        setupCurrentTempToggle(context, views, appWidgetId)
        setupSettingsShortcut(context, views, appWidgetId)

        // Get the current display source for this widget
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val dailyActuals = dailyActualsBySource[displaySource.id].orEmpty()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, " +
                "isEveningMode=$isEveningMode, weatherCount=${weatherList.size}, actualsCount=${dailyActuals.size}, source=${displaySource.id}",
        )

        if (dailyActuals[todayStr] == null) {
            requestMissingActualsRefresh(
                context = context,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                displaySource = displaySource,
                reasonSuffix = "today",
                message = "widget=$appWidgetId source=${displaySource.id} missing today actuals, enqueueing worker",
            )
        }

        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { it.targetDate }
                .mapValues { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }

        // Set API source indicator
        views.setTextViewText(R.id.api_source, displaySource.shortDisplayName)

        // Set weather icon
        val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val todayIconForecast = resolveTodayHeaderForecast(now, hourlyForecasts, displaySource)
        val iconDateTime = todayIconForecast?.let { LocalDateTime.parse(it.dateTime) } ?: now
        val isNight = SunPositionUtils.isNight(iconDateTime, lat, lon)

        val iconRes = WeatherIconMapper.getIconResource(
            condition = todayIconForecast?.condition ?: weatherByDate[todayStr]?.condition,
            isNight = isNight,
            cloudCover = todayIconForecast?.cloudCover,
        )
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        val observedCurrentTemp = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)

        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                observedCurrentTemp = observedCurrentTemp?.temperature,
                observedCurrentTempFetchedAt = observedCurrentTemp?.observedAt,
                storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource),
                currentLat = lat,
                currentLon = lon,
            )
        val resolveMs = SystemClock.elapsedRealtime() - resolveStartMs
        if (currentTempResolution.shouldClearStoredDelta) {
            stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
        }
        currentTempResolution.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it) }
        val currentTemp = currentTempResolution.displayTemp
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)

        if (currentTemp != null) {
            val formattedTemp =
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = currentTemp,
                    numColumns = numColumns,
                    isStaleEstimate = currentTempResolution.isStaleEstimate,
                )
            views.setTextViewText(R.id.current_temp, formattedTemp)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }

        // Show precipitation probability next to current temp when rain is expected
        val todayWeather = weatherByDate[todayStr]
        val precipProb =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = todayWeather?.precipProbability,
                referenceTime = now,
            )
        val isPrecipVisible = precipProb != null && precipProb > 0
        if (isPrecipVisible) {
            views.setTextViewText(R.id.precip_probability, "$precipProb%")
            val textSizeSp = HeaderPrecipCalculator.getPrecipTextSize(precipProb)
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }

        val delta = currentTempResolution.appliedDelta
        val deltaVisible =
            currentTemp != null &&
            !isPrecipVisible &&
            delta != null &&
            kotlin.math.abs(delta) >= DELTA_VISIBILITY_THRESHOLD
        if (deltaVisible) {
            val deltaText = String.format("%+.1f", delta)
            val deltaColor = if (delta > 0) Color.parseColor("#FF6B35") else Color.parseColor("#5AC8FA")
            views.setTextViewText(R.id.current_temp_delta, deltaText)
            views.setTextColor(R.id.current_temp_delta, deltaColor)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }

        // Setup API source toggle click handler
        setupApiToggle(context, views, appWidgetId, numRows)
        Log.d(
            TAG,
            buildHeaderStateLog(
                widgetId = appWidgetId,
                viewMode = com.weatherwidget.widget.ViewMode.DAILY,
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
                deltaHiddenReason = dailyDeltaHiddenReason(currentTemp, delta, isPrecipVisible),
                precipVisible = isPrecipVisible,
                precipProbability = precipProb,
                isNowLineVisible = null,
                offset = dateOffset,
                zoom = null,
                resolveMs = resolveMs,
            ),
        )
        
        // Hide history icon and delta badge in daily mode
        views.setViewVisibility(R.id.home_icon, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone, View.GONE)
        views.setViewVisibility(R.id.history_icon, View.GONE)
        views.setViewVisibility(R.id.history_touch_zone, View.GONE)
        views.setViewVisibility(R.id.current_stations_icon, View.GONE)
        views.setViewVisibility(R.id.current_stations_touch_zone, View.GONE)

        // Set up navigation click handlers
        val availableDates = weatherList.map { it.targetDate }.toSet() + dailyActuals.keys
        val sortedDates = availableDates.mapNotNull { try { LocalDate.parse(it) } catch (e: Exception) { null } }.sorted()
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${sortedDates.firstOrNull()}, maxDate=${sortedDates.lastOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns, isEveningMode)

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        var prepareMs = 0L
        var renderMs = 0L

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)

            val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
            val climateNormals = repository?.getHistoricalNormalsByMonthDay(lat, lon) ?: emptyMap()

            val prepareStartMs = SystemClock.elapsedRealtime()
            val days = DailyViewLogic.prepareGraphDays(
                now, centerDate, today, weatherByDate, forecastSnapshots,
                numColumns, displaySource, isEveningMode, skipHistory,
                hourlyForecasts, stateManager, appWidgetId, precipProb,
                dailyActuals, climateNormals, currentTemps
            )
            prepareMs = SystemClock.elapsedRealtime() - prepareStartMs
            Log.d(TAG, "updateWidget: Graph mode - prepared ${days.size} days for $numColumns columns. Day dates: ${days.map { it.date }}")
            days.forEach { day ->
                Log.d(
                    TAG,
                    "  Day: ${day.date} [${day.label}] High=${day.high}, Low=${day.low}, " +
                        "fcstHigh=${day.forecastHigh}, fcstLow=${day.forecastLow}, " +
                        "snapshotHigh=${day.snapshotHigh}, snapshotLow=${day.snapshotLow}, " +
                        "todayForecastFallback=${day.isTodayForecastFallback}",
                )
            }

            val missingTodaySnapshot = days.firstOrNull { day ->
                day.isToday &&
                    day.forecastHigh != null &&
                    day.forecastLow != null &&
                    day.snapshotHigh == null &&
                    day.snapshotLow == null
            }
            if (missingTodaySnapshot != null) {
                requestMissingDataRefresh(
                    context = context,
                    stateManager = stateManager,
                    appWidgetId = appWidgetId,
                    displaySource = displaySource,
                    refreshType = "today_snapshot",
                    cooldownMs = MISSING_TODAY_SNAPSHOT_REFRESH_COOLDOWN_MS,
                    logTag = "MISSING_TODAY_SNAPSHOT_FETCH",
                    forceRefresh = false,
                    reason = "missing_today_snapshot_${displaySource.id}",
                    message = "widget=$appWidgetId source=${displaySource.id} missing today snapshot for ${missingTodaySnapshot.date}, enqueueing worker",
                )
            }

            val missingVisiblePastActuals = days.firstOrNull { day ->
                day.isPast &&
                    dailyActuals[day.date] == null &&
                    day.forecastHigh != null &&
                    day.forecastLow != null
            }
            if (missingVisiblePastActuals != null) {
                requestMissingActualsRefresh(
                    context = context,
                    stateManager = stateManager,
                    appWidgetId = appWidgetId,
                    displaySource = displaySource,
                    reasonSuffix = "history",
                    message =
                        "widget=$appWidgetId source=${displaySource.id} missing past actuals for ${missingVisiblePastActuals.date}, " +
                            "graphing forecast history and enqueueing worker",
                )
            }

            // Mark rain as shown if today's rain is in the list
            if (days.any { it.isToday && it.rainSummary != null }) {
                stateManager.markRainShown(appWidgetId, todayStr)
            }

            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = true,
                isEveningMode = isEveningMode,
                centerDate = centerDate,
                visibleDates = days.map { it.date },
            )

            // Render graph
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16
            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)
            val rawWidthPx = WidgetSizeCalculator.dpToPx(context, widthDp).coerceAtLeast(1)
            val rawHeightPx = WidgetSizeCalculator.dpToPx(context, heightDp).coerceAtLeast(1)
            val bitmapScale = min(widthPx.toFloat() / rawWidthPx.toFloat(), heightPx.toFloat() / rawHeightPx.toFloat())

            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = DailyForecastGraphRenderer.renderGraph(context, days, widthPx, heightPx, bitmapScale, days.size)
            renderMs = SystemClock.elapsedRealtime() - renderStartMs
            views.setImageViewBitmap(R.id.graph_view, bitmap)

            setupGraphDayClickHandlers(context, views, appWidgetId, now, days, lat, lon, displaySource)
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)

            val visibleDaysInfo = updateTextMode(
                context, views, now, centerDate, today, weatherByDate,
                hourlyForecasts, numColumns, displaySource, skipHistory,
                stateManager, appWidgetId, precipProb, dailyActuals, currentTemps
            )

            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = false,
                isEveningMode = isEveningMode,
                centerDate = centerDate,
                visibleDates = visibleDaysInfo.map { it.second },
            )

            setupTextDayClickHandlers(context, views, appWidgetId, now, visibleDaysInfo, lat, lon, displaySource)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
        val totalMs = SystemClock.elapsedRealtime() - handlerStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
            thresholdMs = WidgetPerfLogger.WIDGET_RENDER_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_WIDGET_RENDER_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to "DAILY",
                "useGraph" to useGraph,
                "resolveMs" to resolveMs,
                "prepareMs" to prepareMs,
                "renderMs" to renderMs,
                "forecastCount" to weatherList.size,
                "hourlyCount" to hourlyForecasts.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    private suspend fun requestMissingActualsRefresh(
        context: Context,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        reasonSuffix: String,
        message: String,
    ) {
        requestMissingDataRefresh(
            context = context,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            displaySource = displaySource,
            refreshType = "actuals_$reasonSuffix",
            cooldownMs = MISSING_ACTUALS_REFRESH_COOLDOWN_MS,
            logTag = "MISSING_ACTUALS_FETCH",
            forceRefresh = true,
            reason = "missing_actuals_${displaySource.id}_$reasonSuffix",
            message = message,
        )
    }

    private suspend fun requestMissingDataRefresh(
        context: Context,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        refreshType: String,
        cooldownMs: Long,
        logTag: String,
        forceRefresh: Boolean,
        reason: String,
        message: String,
    ) {
        if (!stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, refreshType, cooldownMs)) {
            return
        }
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, refreshType)
        WeatherDatabase.getDatabase(context).appLogDao().log(logTag, message, "INFO")
        WeatherWidgetProvider.triggerImmediateUpdate(
            context = context,
            forceRefresh = forceRefresh,
            reason = reason,
        )
    }

    private fun setupCurrentTempToggle(context: Context, views: RemoteViews, appWidgetId: Int) {
        val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.viewToggle(appWidgetId), toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)

        val precipIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_PRECIP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val precipPendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.precipToggle(appWidgetId), precipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)
    }

    private suspend fun logDailyRenderSummary(
        context: Context,
        appWidgetId: Int,
        dateOffset: Int,
        displaySource: WeatherSource,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        isEveningMode: Boolean,
        centerDate: LocalDate,
        visibleDates: List<String>,
    ) {
        val mode = if (useGraph) "GRAPH" else "TEXT"
        val datesSummary = visibleDates.joinToString(",").ifEmpty { "<none>" }
        val tag = if (visibleDates.isEmpty()) "DAILY_RENDER_EMPTY" else "DAILY_RENDER"
        WeatherDatabase.getDatabase(context).appLogDao().log(
            tag,
            "widget=$appWidgetId mode=$mode offset=$dateOffset cols=$numColumns rows=$numRows evening=$isEveningMode center=$centerDate source=${displaySource.id} days=${visibleDates.size} dates=$datesSummary"
        )
    }

    private fun setupSettingsShortcut(context: Context, views: RemoteViews, appWidgetId: Int) {
        val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent = PendingIntent.getActivity(
            context, WidgetRequestCodes.settings(appWidgetId), settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.settings_touch_zone, settingsPendingIntent)
    }

    private fun setupApiToggle(context: Context, views: RemoteViews, appWidgetId: Int, numRows: Int) {
        val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.apiToggle(appWidgetId), toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.api_source_container, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.api_touch_zone, togglePendingIntent)

        val textSizeSp = when {
            numRows >= 3 -> 18f
            numRows >= 2 -> 16f
            else -> 14f
        }
        views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    @VisibleForTesting
    internal fun resolveTodayHeaderForecast(
        now: LocalDateTime,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): HourlyForecastEntity? {
        val today = now.toLocalDate()
        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == displaySource.id }
                        ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                        ?: entry.value.firstOrNull()
                }

        val candidateTimes =
            listOf(
                now.plusHours(1).takeIf { it.toLocalDate() == today },
                now,
            ).filterNotNull()

        return candidateTimes.firstNotNullOfOrNull { candidateTime ->
            forecastsByTime[WeatherTimeUtils.toHourlyForecastKey(candidateTime)]
        }
    }

    private fun setupNavigationButtons(
        context: Context, views: RemoteViews, appWidgetId: Int,
        stateManager: WidgetStateManager, availableDates: Set<String>,
        numColumns: Int, isEveningMode: Boolean
    ) {
        val today = LocalDate.now()
        val sortedDates = availableDates.map { LocalDate.parse(it) }.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        val (leftmost, _) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) - 1, numColumns, isEveningMode)
        val (_, rightmost) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) + 1, numColumns, isEveningMode)

        val canLeft = minDate != null && !minDate.isAfter(leftmost)
        val canRight = maxDate != null && !maxDate.isBefore(rightmost)
        
        Log.d(TAG, "setupNavigationButtons: id=$appWidgetId, leftmostVisibleIfNavLeft=$leftmost, minAvailableDate=$minDate, canLeft=$canLeft")
        Log.d(TAG, "setupNavigationButtons: id=$appWidgetId, rightmostVisibleIfNavRight=$rightmost, maxAvailableDate=$maxDate, canRight=$canRight")

        // Always show the left arrow
        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)
        
        if (canLeft) {
            val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val leftPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_left, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, toastPendingIntent)
        }

        // Always show the right arrow
        views.setViewVisibility(R.id.nav_right, View.VISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)

        if (canRight) {
            val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val rightPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_right, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, toastPendingIntent)
        }
    }

    private fun updateTextMode(
        context: Context, views: RemoteViews, now: LocalDateTime, centerDate: LocalDate,
        today: LocalDate, weatherByDate: Map<String, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>, numColumns: Int,
        displaySource: WeatherSource, skipHistory: Boolean,
        stateManager: WidgetStateManager?, appWidgetId: Int,
        todayNext8HourPrecipProbability: Int?,
        dailyActuals: Map<String, ObservationResolver.DailyActual> = emptyMap(),
        currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList()
    ): List<Triple<Int, String, Boolean>> {
        val dayDataList = DailyViewLogic.prepareTextDays(
            now, centerDate, today, weatherByDate, hourlyForecasts, numColumns,
            displaySource, skipHistory, stateManager, appWidgetId, todayNext8HourPrecipProbability, dailyActuals,
            currentTemps
        )

        val dayIds = listOf(
            DayIds(R.id.day1_container, R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low, R.id.day1_rain),
            DayIds(R.id.day2_container, R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low, R.id.day2_rain),
            DayIds(R.id.day3_container, R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low, R.id.day3_rain),
            DayIds(R.id.day4_container, R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low, R.id.day4_rain),
            DayIds(R.id.day5_container, R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low, R.id.day5_rain),
            DayIds(R.id.day6_container, R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low, R.id.day6_rain),
            DayIds(R.id.day7_container, R.id.day7_label, R.id.day7_icon, R.id.day7_high, R.id.day7_low, R.id.day7_rain),
        )

        dayDataList.forEachIndexed { index, data ->
            val ids = dayIds[index]
            if (data.isVisible) {
                views.setViewVisibility(ids.container, View.VISIBLE)
                populateDay(context, views, now, ids, data, hourlyForecasts, displaySource)
            } else {
                views.setViewVisibility(ids.container, View.GONE)
            }
        }

        if (dayDataList.any { it.isToday && it.rainSummary != null }) {
            stateManager?.markRainShown(appWidgetId, today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        return dayDataList.filter { it.isVisible }.map { Triple(it.dayIndex, it.dateStr, it.hasRainForecast) }
    }

    private fun populateDay(
        context: Context, views: RemoteViews, now: LocalDateTime,
        ids: DayIds, data: DailyViewLogic.TextDayData,
        hourlyForecasts: List<HourlyForecastEntity>, displaySource: WeatherSource
    ) {
        views.setTextViewText(ids.label, data.label)
        
        val iconRes = data.iconRes
        views.setImageViewResource(ids.icon, iconRes)

        if (!WeatherIconMapper.isRainy(iconRes) && !WeatherIconMapper.isMixed(iconRes)) {
            val tintColor = if (WeatherIconMapper.isSunny(iconRes)) {
                context.getColor(R.color.sunny_yellow)
            } else {
                context.getColor(R.color.weather_icon_tint_default)
            }
            views.setInt(ids.icon, "setColorFilter", tintColor)
        }

        views.setViewVisibility(ids.icon, View.VISIBLE)
        views.setTextViewText(ids.high, data.highLabel ?: "--°")
        views.setTextViewText(ids.low, data.lowLabel ?: "--°")

        if (data.showRain && !data.rainSummary.isNullOrEmpty()) {
            views.setTextViewText(ids.rain, "💧 ${data.rainSummary}")
            views.setViewVisibility(ids.rain, View.VISIBLE)
        } else {
            views.setViewVisibility(ids.rain, View.GONE)
        }
    }

    @VisibleForTesting
    internal fun buildDayClickIntent(
        context: Context, appWidgetId: Int, dayIndex: Int, dateStr: String,
        hasRainForecast: Boolean, lat: Double, lon: Double,
        displaySource: WeatherSource, now: LocalDateTime = LocalDateTime.now(),
    ): Intent {
        val targetDay = LocalDate.parse(dateStr)
        val isHistory = targetDay.isBefore(now.toLocalDate())
        val showHistory = DayClickHelper.shouldShowHistory(isHistory)

        return Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", dateStr)
            putExtra("isHistory", isHistory)
            putExtra("showHistory", showHistory)
            putExtra("index", dayIndex)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)

            if (!showHistory) {
                val targetMode = DayClickHelper.resolveTargetViewMode(hasRainForecast)
                val offset = DayClickHelper.calculatePrecipitationOffset(now, targetDay)
                putExtra(EXTRA_TARGET_VIEW, targetMode.name)
                putExtra(EXTRA_HOURLY_OFFSET, offset)
            }
        }
    }

    private fun setupTextDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        visibleDays: List<Triple<Int, String, Boolean>>, lat: Double, lon: Double, displaySource: WeatherSource
    ) {
        val containerIds = listOf(R.id.day1_container, R.id.day2_container, R.id.day3_container, R.id.day4_container, R.id.day5_container, R.id.day6_container, R.id.day7_container)
        visibleDays.forEach { (dayIndex, dateStr, hasRainForecast) ->
            val intent = buildDayClickIntent(context, appWidgetId, dayIndex, dateStr, hasRainForecast, lat, lon, displaySource, now)
            val pendingIntent = PendingIntent.getBroadcast(context, WidgetRequestCodes.dayClick(appWidgetId, dayIndex), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(containerIds[dayIndex - 1], pendingIntent)
        }
    }

    private fun setupGraphDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>, lat: Double, lon: Double, displaySource: WeatherSource
    ) {
        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )
        days.forEachIndexed { index, dayData ->
            val zoneId = zoneIds.getOrNull(index) ?: return@forEachIndexed
            views.setViewVisibility(zoneId, View.VISIBLE)
            val intent = buildDayClickIntent(context, appWidgetId, index + 1, dayData.date, dayData.hasRainForecast, lat, lon, displaySource, now)
            val pendingIntent = PendingIntent.getBroadcast(context, WidgetRequestCodes.graphClick(appWidgetId, index), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }
        for (i in days.size until zoneIds.size) views.setViewVisibility(zoneIds[i], View.GONE)
    }

    private fun dailyDeltaHiddenReason(
        currentTemp: Float?,
        appliedDelta: Float?,
        isPrecipVisible: Boolean,
    ): String? =
        when {
            currentTemp == null -> "current_temp_missing"
            isPrecipVisible -> "precip_supersedes"
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
        zoom: com.weatherwidget.widget.ZoomLevel?,
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
}
