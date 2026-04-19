/*

WeatherWidgetProvider is the main entry point for the home screen widget.
The orchestrator that connects Android widget lifecycle events → database queries → view renderers,
and routes user tap interactions → state changes → re-renders.

It extends AppWidgetProvider and handles:
	Lifecycle — onUpdate (widgets added/update cycle), onEnabled/onDisabled (first/last widget), onDeleted (per-widget cleanup),
	            and onAppWidgetOptionsChanged (resize).
	Data loading — onUpdate opens the Room database, queries forecasts/snapshots/hourly data/current temps/daily extremes,
	               then routes each widget to the appropriate renderer
	               (DailyViewHandler, TemperatureViewHandler, PrecipViewHandler, CloudCoverViewHandler).
	               It also checks staleness and triggers background fetches via WorkManager when needed.
	User interactions — onReceive dispatches intent actions (refresh, navigation, API toggle, view toggle, zoom cycle, day click, etc.)
	                    to handler methods that use goAsync() + coroutines to do work off the main thread.
	Work scheduling — schedulePeriodicUpdate enqueues a periodic WorkManager job (1-hour interval),
	                  triggerImmediateUpdate enqueues one-off workers, and triggerUiOnlyUpdate enqueues a lightweight cache-only refresh.
	                  Each toggle/zoom handler also restarts heartbeat schedulers (restartHeartbeats).
	Rendering — The companion updateWidgetWithData is the central render dispatcher.
	            It reads the widget's view mode, zoom, offset, and display source from WidgetStateManager,
	            resolves the current observed temperature, then delegates to the appropriate view handler.
*/

package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.work.*
import com.weatherwidget.R
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Weather Widget Provider - Main entry point for the widget.
 *
 * This class is responsible for:
 * - Handling widget lifecycle events (onUpdate, onEnabled, onDisabled, etc.)
 * - Routing user interactions (clicks) to appropriate handlers
 * - Delegating view rendering to specialized handler classes
 *
 * The rendering logic has been refactored into handler classes:
 * - [DailyViewHandler]: Handles daily forecast view
 * - [TemperatureViewHandler]: Handles hourly temperature graph
 * - [PrecipViewHandler]: Handles precipitation graph
 * - [WidgetIntentRouter]: Routes intent actions to appropriate handlers
 * - [WidgetSizeCalculator]: Calculates widget dimensions
 */
@dagger.hilt.android.AndroidEntryPoint
class WeatherWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var repository: WeatherRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val now = SystemClock.elapsedRealtime()
        val filteredIds = appWidgetIds.filter { id ->
            val last = lastUpdateByWidgetId[id] ?: 0L
            if (now - last < STARTUP_DEBOUNCE_MS) {
                Log.d(TAG, "onUpdate: Debouncing duplicate update for widget $id")
                false
            } else {
                lastUpdateByWidgetId[id] = now
                true
            }
        }.toIntArray()

        if (filteredIds.isEmpty()) return

        Log.d(TAG, "onUpdate: Updating ${filteredIds.size} widgets")

        val startupToken = WidgetPerfLogger.newToken("startup")
        val onUpdateStartMs = SystemClock.elapsedRealtime()
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val dbOpenStartMs = SystemClock.elapsedRealtime()
            val database = WeatherDatabase.getDatabase(context)
            val dbOpenMs = SystemClock.elapsedRealtime() - dbOpenStartMs
            try {
                val forecastDao = database.forecastDao()
                val hourlyDao = database.hourlyForecastDao()
                val appLogDao = database.appLogDao()
                val latestDbLifecycle = appLogDao.getLatestDatabaseLifecycleEvent()
                WidgetPerfLogger.logIfSlow(
                    appLogDao = appLogDao,
                    thresholdMs = WidgetPerfLogger.DB_OPEN_SLOW_MS,
                    totalMs = dbOpenMs,
                    appLogTag = WidgetPerfLogger.TAG_DB_OPEN_PERF,
                    message = WidgetPerfLogger.kv(
                        "token" to startupToken,
                        "phase" to "onUpdate",
                        "dbOpenMs" to dbOpenMs,
                        "dbEvent" to latestDbLifecycle?.tag,
                        "dbEventTs" to latestDbLifecycle?.timestamp,
                    ),
                    debugTag = TAG,
                )

                // 1. Get latest data from DB to see if we can skip loading state
                val latestWeatherStartMs = SystemClock.elapsedRealtime()
                val latestWeather = forecastDao.getLatestWeather()
                val latestWeatherMs = SystemClock.elapsedRealtime() - latestWeatherStartMs
                var forecastQueryMs = 0L
                var snapshotQueryMs = 0L
                var hourlyQueryMs = 0L
                var currentTempQueryMs = 0L
                var extremesQueryMs = 0L
                var staleCheckMs = 0L
                val stateManager = WidgetStateManager(context)
                val widgetViewModes =
                    filteredIds
                        .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                        .associateWith { stateManager.getViewMode(it) }
                val needsDailyData = needsDailyStartupData(widgetViewModes.values)
                appLogDao.log("WIDGET_LIFECYCLE", "phase=onUpdate_entry hasData=${latestWeather != null} count=${filteredIds.size} thread=${Thread.currentThread().name}")

                if (latestWeather == null) {
                    // No data at all, show loading for all widgets
                    for (appWidgetId in filteredIds) {
                        WidgetRenderer.updateWidgetLoading(context, appWidgetManager, appWidgetId)
                    }
                    triggerImmediateUpdate(context, reason = "on_update_no_data")
                } else {
                    // We have some data, refresh all widgets from cache immediately
                    val historyStart = LocalDate.now().minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
                    val thirtyDays = LocalDate.now().plusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY

                    val forecastQueryStartMs = SystemClock.elapsedRealtime()
                    val weatherList =
                        forecastDao.getForecastsInRange(
                            historyStart,
                            thirtyDays,
                            latestWeather.locationLat,
                            latestWeather.locationLon,
                        )
                    forecastQueryMs = SystemClock.elapsedRealtime() - forecastQueryStartMs
                    val forecastSnapshots =
                        if (needsDailyData) {
                            val snapshotQueryStartMs = SystemClock.elapsedRealtime()
                            forecastDao.getAllForecastsInRange(historyStart, thirtyDays, latestWeather.locationLat, latestWeather.locationLon)
                                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                                .also {
                                    snapshotQueryMs = SystemClock.elapsedRealtime() - snapshotQueryStartMs
                                }
                        } else {
                            emptyMap()
                        }

                    // Get hourly forecasts for interpolation and rain analysis
                    val nowLocal = LocalDateTime.now()
                    val zoneId = ZoneId.systemDefault()
                    val hourlyStart = nowLocal.minusHours(HOURLY_LOOKBACK_HOURS).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
                    val hourlyEnd = nowLocal.plusHours(HOURLY_GRAPH_LOOKAHEAD_HOURS).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
                    val hourlyQueryStartMs = SystemClock.elapsedRealtime()
                    val hourlyForecasts =
                        hourlyDao.getHourlyForecasts(
                            hourlyStart,
                            hourlyEnd,
                            latestWeather.locationLat,
                            latestWeather.locationLon,
                        )
                    hourlyQueryMs = SystemClock.elapsedRealtime() - hourlyQueryStartMs

                    val currentTempQueryStartMs = SystemClock.elapsedRealtime()
                    val querySinceMs = nowLocal.minusHours(HOURLY_LOOKBACK_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val currentTemps = repository.getMainObservationsWithComputedNwsBlend(
                        latestWeather.locationLat,
                        latestWeather.locationLon,
                        querySinceMs,
                    )
                    currentTempQueryMs = SystemClock.elapsedRealtime() - currentTempQueryStartMs

                    val dailyActualsBySource =
                        if (needsDailyData) {
                            val extremesQueryStartMs = SystemClock.elapsedRealtime()
                            val actuals = repository.getDailyActualsWithLiveToday(
                                latestWeather.locationLat,
                                latestWeather.locationLon,
                            )
                            extremesQueryMs = SystemClock.elapsedRealtime() - extremesQueryStartMs
                            actuals
                        } else {
                            emptyMap()
                        }

                    for (appWidgetId in filteredIds) {
                        val job = launch {
                            WidgetRenderer.updateWidgetWithData(
                                context = context,
                                appWidgetManager = appWidgetManager,
                                appWidgetId = appWidgetId,
                                weatherList = weatherList,
                                forecastSnapshots = forecastSnapshots,
                                hourlyForecasts = hourlyForecasts,
                                currentTemps = currentTemps,
                                dailyActualsBySource = dailyActualsBySource,
                                repository = repository,
                                startupToken = startupToken,
                            )
                        }
                        WidgetUpdateTracker.trackJob(appWidgetId, job, WidgetUpdateTracker.JobType.UI_PAINT)
                    }

                    // 2. Check if data is stale and needs background fetch
                    val staleCheckStartMs = SystemClock.elapsedRealtime()
                    if (DataFreshness.isDataStale(context)) {
                        Log.d(TAG, "onUpdate: Data is stale, deferring background fetch until after startup paint")
                        triggerImmediateUpdate(
                            context,
                            reason = "on_update_stale",
                            initialDelayMs = STARTUP_STALE_REFRESH_DELAY_MS,
                        )
                    } else {
                        Log.d(TAG, "onUpdate: Data is fresh, skipped fetch")
                    }
                    staleCheckMs = SystemClock.elapsedRealtime() - staleCheckStartMs
                }

                schedulePeriodicUpdate(context)
                val totalMs = SystemClock.elapsedRealtime() - onUpdateStartMs
                WidgetPerfLogger.logIfSlow(
                    appLogDao = appLogDao,
                    thresholdMs = WidgetPerfLogger.STARTUP_SLOW_MS,
                    totalMs = totalMs,
                    appLogTag = WidgetPerfLogger.TAG_WIDGET_STARTUP_PERF,
                    message = WidgetPerfLogger.kv(
                        "token" to startupToken,
                        "widgets" to filteredIds.size,
                        "dbOpenMs" to dbOpenMs,
                        "latestWeatherMs" to latestWeatherMs,
                        "forecastMs" to forecastQueryMs,
                        "snapshotsMs" to snapshotQueryMs,
                        "hourlyMs" to hourlyQueryMs,
                        "currentTempMs" to currentTempQueryMs,
                        "extremesMs" to extremesQueryMs,
                        "staleCheckMs" to staleCheckMs,
                        "totalMs" to totalMs,
                        "dbEvent" to latestDbLifecycle?.tag,
                    ),
                    debugTag = TAG,
                )
            } catch (e: CancellationException) {
                database.appLogDao().log("WIDGET_LIFECYCLE", "phase=onUpdate_cancelled msg=${e.message}", "VERBOSE")
            } catch (e: Exception) {
                database.appLogDao().log("WIDGET_EXCEPTION", "${e.javaClass.simpleName}: ${e.message}", "ERROR")
            } finally {
                finishPendingResultSafely(pendingResult, "onUpdate")
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.d(TAG, "onAppWidgetOptionsChanged: widgetId=$appWidgetId")
        val job = launchAsync {
            WidgetIntentRouter.handleResize(context, appWidgetId, repository)
        }
        WidgetUpdateTracker.trackJob(appWidgetId, job, WidgetUpdateTracker.JobType.INTERACTION)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicUpdate(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_CURRENT_TEMP)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_NWS_TERMINAL_CATCHUP)

        val uiScheduler = UIUpdateScheduler(context)
        uiScheduler.cancelScheduledUpdates()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.cancelOpportunisticUpdate(context)
        }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        super.onDeleted(context, appWidgetIds)
        val stateManager = WidgetStateManager(context)
        for (appWidgetId in appWidgetIds) {
            stateManager.clearWidgetState(appWidgetId)
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: action=${intent.action}")

        when (intent.action) {
            ACTION_REFRESH -> handleRefreshAction(context, intent)
            ACTION_NAV_LEFT, ACTION_NAV_RIGHT -> handleNavigationAction(context, intent)
            ACTION_TOGGLE_API -> handleToggleApiAction(context, intent)
            ACTION_TOGGLE_VIEW -> handleToggleViewAction(context, intent)
            ACTION_TOGGLE_PRECIP -> handleTogglePrecipAction(context, intent)
            ACTION_CYCLE_ZOOM -> handleCycleZoomAction(context, intent)
            ACTION_SET_VIEW -> handleSetViewAction(context, intent)
            ACTION_DAY_CLICK -> handleDayClickAction(context, intent)
            ACTION_SHOW_TOAST -> handleShowToastAction(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> triggerUiOnlyUpdate(context, reason = "package_replaced")
        }
    }

    private fun handleShowToastAction(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_TOAST_MESSAGE) ?: "No additional data"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun handleDayClickAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        val dateStr = intent.getStringExtra("date") ?: ""
        val isHistory = intent.getBooleanExtra("isHistory", false)
        val index = intent.getIntExtra("index", -1)
        val showHistory = intent.getBooleanExtra("showHistory", isHistory) // Default to isHistory for backward compat

        Log.d(TAG, "handleDayClickAction: widget=$appWidgetId, date=$dateStr, isHistory=$isHistory, showHistory=$showHistory, index=$index")

        val receiveTimeMs = System.currentTimeMillis()
        launchAsync {
            val coroutineStartMs = System.currentTimeMillis()
            val database = WeatherDatabase.getDatabase(context)
            database.appLogDao().log("CLICK_DAILY", "index=$index, date=$dateStr, isHistory=$isHistory, showHistory=$showHistory")

            if (showHistory) {
                val lat = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0)
                val lon = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0)
                val source = intent.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE) ?: ""

                val historyIntent = Intent(context, ForecastHistoryActivity::class.java).apply {
                    putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, dateStr)
                    putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                    putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                    putExtra(ForecastHistoryActivity.EXTRA_SOURCE, source)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(historyIntent)
                val totalMs = System.currentTimeMillis() - receiveTimeMs
                val coroutineDelayMs = coroutineStartMs - receiveTimeMs
                database.appLogDao().log("CLICK_TIMING", "widget=$appWidgetId branch=history total=${totalMs}ms coroutineDelay=${coroutineDelayMs}ms")
                if (totalMs > 500) {
                    database.appLogDao().log("CLICK_SLOW", "widget=$appWidgetId branch=history total=${totalMs}ms coroutineDelay=${coroutineDelayMs}ms date=$dateStr")
                }
            } else {
                val targetViewName = intent.getStringExtra(EXTRA_TARGET_VIEW) ?: "PRECIPITATION"
                val targetOffset = intent.getIntExtra(EXTRA_HOURLY_OFFSET, 0)
                val targetMode =
                    try {
                        ViewMode.valueOf(targetViewName)
                    } catch (_: Exception) {
                        ViewMode.PRECIPITATION
                    }
                val hasHourlyData =
                    hasHourlyDataForDate(
                        context = context,
                        database = database,
                        appWidgetId = appWidgetId,
                        dateStr = dateStr,
                        intent = intent,
                    )
                if (!hasHourlyData && (targetMode == ViewMode.PRECIPITATION || targetMode == ViewMode.TEMPERATURE || targetMode == ViewMode.CLOUD_COVER)) {
                    database.appLogDao().log(
                        "CLICK_DAILY_NO_HOURLY",
                        "date=$dateStr mode=$targetMode -> settings",
                    )
                    val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                } else {
                    val stateManager = WidgetStateManager(context)
                    Log.d(TAG, "handleDayClickAction: about to handleSetView targetMode=$targetMode offset=$targetOffset currentStoredMode=${stateManager.getViewMode(appWidgetId)} currentStoredZoom=${stateManager.getZoomLevel(appWidgetId)}")
                    if (targetMode == ViewMode.PRECIPITATION) {
                        stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
                    }
                    WidgetIntentRouter.handleSetView(context, appWidgetId, targetMode, targetOffset, repository)
                    val totalMs = System.currentTimeMillis() - receiveTimeMs
                    val coroutineDelayMs = coroutineStartMs - receiveTimeMs
                    database.appLogDao().log("CLICK_TIMING", "widget=$appWidgetId branch=hourly total=${totalMs}ms coroutineDelay=${coroutineDelayMs}ms")
                    if (totalMs > 500) {
                        database.appLogDao().log("CLICK_SLOW", "widget=$appWidgetId branch=hourly total=${totalMs}ms coroutineDelay=${coroutineDelayMs}ms date=$dateStr")
                    }
                }
            }
        }
    }

    private suspend fun hasHourlyDataForDate(
        context: Context,
        database: WeatherDatabase,
        appWidgetId: Int,
        dateStr: String,
        intent: Intent,
    ): Boolean {
        val targetDate =
            try {
                LocalDate.parse(dateStr)
            } catch (_: Exception) {
                return false
            }

        val lat = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0)
        val latestWeather = database.forecastDao().getLatestWeather()
        val effectiveLat = if (lat != 0.0) lat else latestWeather?.locationLat ?: return false
        val effectiveLon = if (lon != 0.0) lon else latestWeather?.locationLon ?: return false

        val zoneId = ZoneId.systemDefault()
        val startMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = targetDate.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli()
        val hourlyForDay = database.hourlyForecastDao().getHourlyForecasts(startMs, endMs, effectiveLat, effectiveLon)
        if (hourlyForDay.isEmpty()) return false

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return true
        val displaySource = WidgetStateManager(context).getCurrentDisplaySource(appWidgetId).id
        return hourlyForDay.any { it.source == displaySource || it.source == WeatherSource.GENERIC_GAP.id }
    }

    private fun handleRefreshAction(
        context: Context,
        intent: Intent,
    ) {
        val uiOnly = intent.getBooleanExtra(EXTRA_UI_ONLY, false)
        Log.d(TAG, "onReceive: Refresh triggered (uiOnly=$uiOnly)")

        launchAsync {
            UIUpdateScheduler(context).scheduleNextUpdate()
            
            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging, powerManager.isInteractive)) {
                CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context)
            }

            val isDataStale = DataFreshness.isDataStale(context)
            val freshnessSummary = DataFreshness.getVisibleSourceFreshnessSummary(context)
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "REFRESH_DECISION",
                "uiOnlyRequested=$uiOnly charging=$isCharging interactive=${powerManager.isInteractive} " +
                    "isDataStale=$isDataStale $freshnessSummary",
                "INFO",
            )
            if (uiOnly || !WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)) {
                triggerUiOnlyUpdate(context, reason = "refresh_action_ui_only")
                Log.d(TAG, "onReceive: UI-only refresh path (uiOnly=$uiOnly, stale=$isDataStale)")
            } else {
                val tempWorkRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "manual_refresh")
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_ONE_TIME + "_current_temp",
                    ExistingWorkPolicy.REPLACE,
                    tempWorkRequest
                )
                Log.d(TAG, "onReceive: Data is stale, triggering background fetch")
                triggerImmediateUpdate(context, forceRefresh = true, reason = "refresh_action_stale")
            }
        }
    }

    private fun handleNavigationAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        Log.d(TAG, "onReceive: Navigation action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val isLeft = intent.action == ACTION_NAV_LEFT
            launchAsync {
                WidgetIntentRouter.handleNavigation(context, appWidgetId, isLeft, repository)
            }
        }
    }

    private fun handleToggleApiAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        Log.d(TAG, "onReceive: Toggle API action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            launchAsync {
                WidgetIntentRouter.handleToggleApi(context, appWidgetId, repository)
                restartHeartbeats(context)
            }
        }
    }

    private fun handleToggleViewAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        Log.d(TAG, "onReceive: Toggle View action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val interactionSource = intent.getStringExtra(EXTRA_INTERACTION_SOURCE) ?: "unknown"
            val receiveTimeMs = System.currentTimeMillis()
            launchAsync {
                val database = WeatherDatabase.getDatabase(context)
                val handlerStartMs = SystemClock.elapsedRealtime()
                WidgetIntentRouter.handleToggleView(context, appWidgetId, repository)
                val handlerMs = SystemClock.elapsedRealtime() - handlerStartMs
                    
                restartHeartbeats(context)

                val totalMs = System.currentTimeMillis() - receiveTimeMs
                database.appLogDao().log(
                    "TOGGLE_VIEW_TIMING",
                    "widget=$appWidgetId source=$interactionSource total=${totalMs}ms handler=${handlerMs}ms",
                )
                if (totalMs > 500) {
                    database.appLogDao().log(
                        "TOGGLE_VIEW_SLOW",
                        "widget=$appWidgetId source=$interactionSource total=${totalMs}ms handler=${handlerMs}ms",
                    )
                }
            }
        }
    }

    private fun handleTogglePrecipAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        Log.d(TAG, "onReceive: Toggle Precip action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            launchAsync {
                WidgetIntentRouter.handleTogglePrecip(context, appWidgetId, repository)
                restartHeartbeats(context)
            }
        }
    }

    private fun handleCycleZoomAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        val zoomCenterOffset = if (intent.hasExtra(EXTRA_ZOOM_CENTER_OFFSET)) {
            intent.getIntExtra(EXTRA_ZOOM_CENTER_OFFSET, 0)
        } else {
            null
        }
        val stateManager = WidgetStateManager(context)
        val currentMode = stateManager.getViewMode(appWidgetId)
        val currentZoom = stateManager.getZoomLevel(appWidgetId)
        Log.d(TAG, "handleCycleZoomAction: widget=$appWidgetId centerOffset=$zoomCenterOffset currentMode=$currentMode currentZoom=$currentZoom")
        if (currentMode == ViewMode.DAILY) {
            Log.e(TAG, "BUG: CYCLE_ZOOM fired while in DAILY mode! This should be ACTION_DAY_CLICK. Extras: ${intent.extras}")
        }
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            launchAsync {
                WidgetIntentRouter.handleCycleZoom(context, appWidgetId, zoomCenterOffset, repository)
                restartHeartbeats(context)
            }
        }
    }

    private fun handleSetViewAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = getWidgetId(intent)
        val targetViewName = intent.getStringExtra(EXTRA_TARGET_VIEW) ?: ""
        val targetOffset = intent.getIntExtra(EXTRA_HOURLY_OFFSET, Int.MIN_VALUE)
        Log.d(TAG, "onReceive: Set View action for widget $appWidgetId, target=$targetViewName, offset=$targetOffset")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            launchAsync {
                val targetMode =
                    try {
                        ViewMode.valueOf(targetViewName)
                    } catch (_: Exception) {
                        ViewMode.DAILY
                    }
                WidgetIntentRouter.handleSetView(context, appWidgetId, targetMode, targetOffset, repository)
            }
        }
    }

    private suspend fun restartHeartbeats(context: Context) {
        UIUpdateScheduler(context).scheduleNextUpdate()
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging, powerManager.isInteractive)) {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context)
        }
    }

    private fun getWidgetId(intent: Intent): Int =
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    private fun launchAsync(block: suspend CoroutineScope.() -> Unit): Job {
        val pendingResult = goAsync()
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } catch (e: CancellationException) {
                Log.d(TAG, "launchAsync cancelled: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "launchAsync failed", e)
            } finally {
                finishPendingResultSafely(pendingResult, "launchAsync")
            }
        }
    }

    private fun schedulePeriodicUpdate(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val workRequest =
            PeriodicWorkRequestBuilder<WeatherWidgetWorker>(
                1,
                TimeUnit.HOURS,
            )
                .setInputData(
                    Data.Builder()
                        .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "periodic_one_hour")
                        .build()
                )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
        CoroutineScope(Dispatchers.IO).launch {
            val nextWindowStartMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "PERIODIC_REFRESH_SCHEDULE",
                "name=$WORK_NAME intervalMinutes=60 policy=keep nextWindowStartMs=$nextWindowStartMs",
                "INFO",
            )
        }
    }

    private fun triggerUiOnlyUpdate(context: Context, reason: String = "unspecified") {
        Log.d(TAG, "triggerUiOnlyUpdate: Enqueueing UI-only worker (reason=$reason)")
        val workRequest =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                        .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONE_TIME + "_ui",
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
        Log.d(TAG, "triggerUiOnlyUpdate: Worker enqueued with id=${workRequest.id}")
    }

    companion object {
        /** Hours of past hourly data to query — covers yesterday's actuals for rain analysis. */
        const val HOURLY_LOOKBACK_HOURS = 24L
        /** Hours of future hourly data to query — covers today + 2 days for rain analysis. */
        const val HOURLY_LOOKAHEAD_HOURS = 60L
        /**
         * Hours of future hourly data to query when passing data to widget rendering.
         * Must cover the full 7-day daily forecast range so the hourly graph works for any tapped day.
         * The precipitation/temperature/cloud-cover graph can be scrolled to offset ~154h (day 7),
         * so the display window (offset ± 12h) requires data up to ~168h ahead.
         */
        const val HOURLY_GRAPH_LOOKAHEAD_HOURS = 168L
        const val WORK_NAME = "weather_widget_update"
        const val WORK_NAME_ONE_TIME = "weather_widget_one_time"
        const val WORK_NAME_CURRENT_TEMP = "weather_widget_current_temp"
        const val WORK_NAME_OBSERVATION_BACKFILL = "weather_widget_observation_backfill"
        const val WORK_NAME_NWS_TERMINAL_CATCHUP = "weather_widget_nws_terminal_catch_up"

        private val lastUpdateByWidgetId = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private const val STARTUP_DEBOUNCE_MS = 500L

        const val ACTION_REFRESH = "com.weatherwidget.ACTION_REFRESH"
        const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
        const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
        const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
        const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
        const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
        const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
        const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"
        const val ACTION_DAY_CLICK = "com.weatherwidget.ACTION_DAY_CLICK"
        const val ACTION_SHOW_TOAST = "com.weatherwidget.ACTION_SHOW_TOAST"
        const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"
        const val EXTRA_HOURLY_OFFSET = "com.weatherwidget.EXTRA_HOURLY_OFFSET"
        const val EXTRA_UI_ONLY = "com.weatherwidget.EXTRA_UI_ONLY"
        const val EXTRA_ZOOM_CENTER_OFFSET = "com.weatherwidget.EXTRA_ZOOM_CENTER_OFFSET"
        const val EXTRA_TOAST_MESSAGE = "com.weatherwidget.EXTRA_TOAST_MESSAGE"
        const val HOUR_ZONE_COUNT = 13
        private const val STARTUP_STALE_REFRESH_DELAY_MS = 1_500L

        internal fun needsDailyStartupData(viewModes: Collection<ViewMode>): Boolean =
            viewModes.any { it == ViewMode.DAILY }

        internal fun triggerImmediateUpdate(
            context: Context,
            forceRefresh: Boolean = false,
            reason: String = "unspecified",
            initialDelayMs: Long = 0L,
        ) {
            Log.d(
                TAG,
                "triggerImmediateUpdate: Enqueueing full/forced worker (reason=$reason, force=$forceRefresh, delayMs=$initialDelayMs)",
            )
            val builder =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, forceRefresh)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                            .build(),
                    )
            if (initialDelayMs > 0) {
                builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            } else {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val workRequest = builder.build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
            Log.d(TAG, "triggerImmediateUpdate: Worker enqueued with id=${workRequest.id}")
        }

        /**
         * Calculate the hourly offset that a zone's center represents.
         * WIDE view spans roughly 24h (-12 to +12 from current offset), split into 13 zones.
         * NARROW view spans roughly 4h (-2 to +2), split into 13 zones.
         * We use an odd number of zones (13) so the visual center (index 6) maps to offset 0.
         * @param zoneIndex 0-based zone index (0..12, left to right)
         * @param currentHourlyOffset the widget's current hourly offset
         * @param zoom the current zoom level of the widget
         * @return the offset to center on when zooming into/out of this zone
         */
        fun zoneIndexToOffset(zoneIndex: Int, currentHourlyOffset: Int, zoom: ZoomLevel = ZoomLevel.WIDE): Int {
            return if (zoom == ZoomLevel.WIDE) {
                // 13 zones covering 24 hours. Index 6 is the visual center (offset 0).
                // Each zone represents ~2 hours. 2 * (6 - 6) = 0.
                currentHourlyOffset + 2 * (zoneIndex - 6)
            } else {
                // 13 zones covering 4 hours. Index 6 is the visual center (offset 0).
                // Each zone is 1/3h. 4/12 * (index - 6) = (index - 6) / 3.
                val offsetFloat = (zoneIndex - 6f) / 3f
                currentHourlyOffset + offsetFloat.roundToInt()
            }
        }
        private const val TAG = "WeatherWidgetProvider"
        const val EXTRA_INTERACTION_SOURCE = "com.weatherwidget.EXTRA_INTERACTION_SOURCE"

        @VisibleForTesting
        internal fun finishPendingResultSafely(
            pendingResult: BroadcastReceiver.PendingResult?,
            caller: String,
        ) {
            if (pendingResult == null) {
                Log.w(TAG, "$caller: goAsync returned null; no pending result to finish")
                return
            }

            try {
                pendingResult.finish()
            } catch (e: Exception) {
                Log.e(TAG, "$caller: failed to finish pending result", e)
            }
        }
    }
}
