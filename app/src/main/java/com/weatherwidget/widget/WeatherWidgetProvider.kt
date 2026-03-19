package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.weatherwidget.R
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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
    @javax.inject.Inject
    lateinit var repository: com.weatherwidget.data.repository.WeatherRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.d(TAG, "onUpdate: Updating ${appWidgetIds.size} widgets")

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
                    appWidgetIds
                        .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                        .associateWith { stateManager.getViewMode(it) }
                val needsDailyData = needsDailyStartupData(widgetViewModes.values)

                if (latestWeather == null) {
                    // No data at all, show loading for all widgets
                    for (appWidgetId in appWidgetIds) {
                        updateWidgetLoading(context, appWidgetManager, appWidgetId)
                    }
                    triggerImmediateUpdate(context, reason = "on_update_no_data")
                } else {
                    // We have some data, refresh all widgets from cache immediately
                    val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)

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
                                .groupBy { it.targetDate }
                                .also {
                                    snapshotQueryMs = SystemClock.elapsedRealtime() - snapshotQueryStartMs
                                }
                        } else {
                            emptyMap()
                        }

                    // Get hourly forecasts for interpolation and rain analysis
                    val now = LocalDateTime.now()
                    val hourlyStart = now.minusHours(HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    val hourlyEnd = now.plusHours(HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    val hourlyQueryStartMs = SystemClock.elapsedRealtime()
                    val hourlyForecasts =
                        hourlyDao.getHourlyForecasts(
                            hourlyStart,
                            hourlyEnd,
                            latestWeather.locationLat,
                            latestWeather.locationLon,
                        )
                    hourlyQueryMs = SystemClock.elapsedRealtime() - hourlyQueryStartMs

                    val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val currentTempQueryStartMs = SystemClock.elapsedRealtime()
                    val currentTemps = database.currentTempDao().getCurrentTemps(
                        todayStr,
                        latestWeather.locationLat,
                        latestWeather.locationLon,
                    )
                    currentTempQueryMs = SystemClock.elapsedRealtime() - currentTempQueryStartMs

                    val dailyActualsBySource =
                        if (needsDailyData) {
                            val historyStartDate = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                            val tomorrowDate = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                            val extremesQueryStartMs = SystemClock.elapsedRealtime()
                            val extremes = database.dailyExtremeDao().getExtremesInRange(
                                historyStartDate,
                                tomorrowDate,
                                latestWeather.locationLat,
                                latestWeather.locationLon,
                            )
                            extremesQueryMs = SystemClock.elapsedRealtime() - extremesQueryStartMs
                            ObservationResolver.extremesToDailyActualsBySource(extremes)
                        } else {
                            emptyMap()
                        }

                    for (appWidgetId in appWidgetIds) {
                        val job = launch {
                            updateWidgetWithData(
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
                        WidgetUpdateTracker.trackJob(appWidgetId, job)
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
                        "widgets" to appWidgetIds.size,
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
            } catch (e: Exception) {
                database.appLogDao().log("WIDGET_EXCEPTION", "${e.javaClass.simpleName}: ${e.message}", "ERROR")
            } finally {
                pendingResult.finish()
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
        val pendingResult = goAsync()
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetIntentRouter.handleResize(context, appWidgetId, repository)
            } finally {
                pendingResult.finish()
            }
        }
        WidgetUpdateTracker.trackJob(appWidgetId, job)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_CURRENT_TEMP)

        val uiScheduler = UIUpdateScheduler(context)
        uiScheduler.cancelScheduledUpdates()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
            ACTION_SHOW_OBSERVATIONS -> handleShowObservationsAction(context, intent)
            ACTION_SHOW_TOAST -> handleShowToastAction(context, intent)
        }
    }

    private fun handleShowToastAction(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_TOAST_MESSAGE) ?: "No additional data"
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun handleShowObservationsAction(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val intentToActivity = Intent(context, com.weatherwidget.ui.WeatherObservationsActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intentToActivity)
    }

    private fun handleDayClickAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val dateStr = intent.getStringExtra("date") ?: ""
        val isHistory = intent.getBooleanExtra("isHistory", false)
        val index = intent.getIntExtra("index", -1)
        val showHistory = intent.getBooleanExtra("showHistory", isHistory) // Default to isHistory for backward compat

        Log.d(TAG, "handleDayClickAction: widget=$appWidgetId, date=$dateStr, isHistory=$isHistory, showHistory=$showHistory, index=$index")

        val receiveTimeMs = System.currentTimeMillis()
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val coroutineStartMs = System.currentTimeMillis()
            try {
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
                    // Future day click was already setup with ACTION_SET_VIEW extras
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
                    if (!hasHourlyData && (targetMode == ViewMode.PRECIPITATION || targetMode == ViewMode.TEMPERATURE)) {
                        database.appLogDao().log(
                            "CLICK_DAILY_NO_HOURLY",
                            "date=$dateStr mode=$targetMode -> settings",
                        )
                        val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(settingsIntent)
                        return@launch
                    }
                    Log.d(TAG, "handleDayClickAction: about to handleSetView targetMode=$targetMode offset=$targetOffset currentStoredMode=${WidgetStateManager(context).getViewMode(appWidgetId)} currentStoredZoom=${WidgetStateManager(context).getZoomLevel(appWidgetId)}")
                    WidgetIntentRouter.handleSetView(context, appWidgetId, targetMode, targetOffset, repository)
                    val totalMs = System.currentTimeMillis() - receiveTimeMs
                    val coroutineDelayMs = coroutineStartMs - receiveTimeMs
                    database.appLogDao().log("CLICK_TIMING", "widget=$appWidgetId branch=hourly total=${totalMs}ms coroutineDelay=${coroutineDelayMs}ms")
                    if (totalMs > 500) {
                        database.appLogDao().log("CLICK_SLOW", "widget=$appWidgetId branch=hourly total=${totalMs}ms coroutineDelay=${coroutineDelayMs}ms date=$dateStr")
                    }
                }
            } finally {
                pendingResult.finish()
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

        val start = "${targetDate}T00:00"
        val end = "${targetDate}T23:00"
        val hourlyForDay = database.hourlyForecastDao().getHourlyForecasts(start, end, effectiveLat, effectiveLon)
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

        triggerUiOnlyUpdate(context, reason = "refresh_action_ui_only")

        if (uiOnly) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isDataStale = DataFreshness.isDataStale(context)
                if (WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)) {
                    Log.d(TAG, "onReceive: Data is stale, triggering background fetch")
                    triggerImmediateUpdate(context, forceRefresh = true, reason = "refresh_action_stale")
                } else {
                    Log.d(TAG, "onReceive: UI-only refresh path (uiOnly=$uiOnly, stale=$isDataStale)")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleNavigationAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        Log.d(TAG, "onReceive: Navigation action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pendingResult = goAsync()
            val isLeft = intent.action == ACTION_NAV_LEFT
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WidgetIntentRouter.handleNavigation(context, appWidgetId, isLeft, repository)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun handleToggleApiAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        Log.d(TAG, "onReceive: Toggle API action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WidgetIntentRouter.handleToggleApi(context, appWidgetId, repository)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun handleToggleViewAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        Log.d(TAG, "onReceive: Toggle View action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val interactionSource = intent.getStringExtra(EXTRA_INTERACTION_SOURCE) ?: "unknown"
            val receiveTimeMs = System.currentTimeMillis()
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = WeatherDatabase.getDatabase(context)
                    val handlerStartMs = SystemClock.elapsedRealtime()
                    WidgetIntentRouter.handleToggleView(context, appWidgetId, repository)
                    val handlerMs = SystemClock.elapsedRealtime() - handlerStartMs
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
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun handleTogglePrecipAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        Log.d(TAG, "onReceive: Toggle Precip action for widget $appWidgetId")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WidgetIntentRouter.handleTogglePrecip(context, appWidgetId, repository)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun handleCycleZoomAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val zoomCenterOffset = if (intent.hasExtra(EXTRA_ZOOM_CENTER_OFFSET)) {
            intent.getIntExtra(EXTRA_ZOOM_CENTER_OFFSET, 0)
        } else {
            null
        }
        val currentMode = WidgetStateManager(context).getViewMode(appWidgetId)
        val currentZoom = WidgetStateManager(context).getZoomLevel(appWidgetId)
        Log.d(TAG, "handleCycleZoomAction: widget=$appWidgetId centerOffset=$zoomCenterOffset currentMode=$currentMode currentZoom=$currentZoom")
        if (currentMode == com.weatherwidget.widget.ViewMode.DAILY) {
            Log.e(TAG, "BUG: CYCLE_ZOOM fired while in DAILY mode! This should be ACTION_DAY_CLICK. Extras: ${intent.extras}")
        }
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WidgetIntentRouter.handleCycleZoom(context, appWidgetId, zoomCenterOffset, repository)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun handleSetViewAction(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val targetViewName = intent.getStringExtra(EXTRA_TARGET_VIEW) ?: ""
        val targetOffset = intent.getIntExtra(EXTRA_HOURLY_OFFSET, Int.MIN_VALUE)
        Log.d(TAG, "onReceive: Set View action for widget $appWidgetId, target=$targetViewName, offset=$targetOffset")
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val targetMode =
                        try {
                            ViewMode.valueOf(targetViewName)
                        } catch (_: Exception) {
                            ViewMode.DAILY
                        }
                    WidgetIntentRouter.handleSetView(context, appWidgetId, targetMode, targetOffset, repository)
                    } finally {

                    pendingResult.finish()
                }
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
        const val WORK_NAME = "weather_widget_update"
        const val WORK_NAME_ONE_TIME = "weather_widget_one_time"
        const val WORK_NAME_CURRENT_TEMP = "weather_widget_current_temp"
        const val WORK_NAME_OBSERVATION_BACKFILL = "weather_widget_observation_backfill"
        const val ACTION_REFRESH = "com.weatherwidget.ACTION_REFRESH"
        const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
        const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
        const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
        const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
        const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
        const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
        const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"
        const val ACTION_DAY_CLICK = "com.weatherwidget.ACTION_DAY_CLICK"
        const val ACTION_SHOW_OBSERVATIONS = "com.weatherwidget.ACTION_SHOW_OBSERVATIONS"
        const val ACTION_SHOW_TOAST = "com.weatherwidget.ACTION_SHOW_TOAST"
        const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"
        const val EXTRA_HOURLY_OFFSET = "com.weatherwidget.EXTRA_HOURLY_OFFSET"
        const val EXTRA_UI_ONLY = "com.weatherwidget.EXTRA_UI_ONLY"
        const val EXTRA_ZOOM_CENTER_OFFSET = "com.weatherwidget.EXTRA_ZOOM_CENTER_OFFSET"
        const val EXTRA_TOAST_MESSAGE = "com.weatherwidget.EXTRA_TOAST_MESSAGE"
        const val HOUR_ZONE_COUNT = 12
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
         * WIDE view spans roughly 24h (-8 to +16 from current offset), split into 12 zones of 2h each.
         * NARROW view spans roughly 4h (-2 to +2), split into 12 zones of 1/3h each.
         * We bias the selected offset so the tapped hour appears centered after switching zooms.
         * @param zoneIndex 0-based zone index (0..11, left to right)
         * @param currentHourlyOffset the widget's current hourly offset
         * @param zoom the current zoom level of the widget
         * @return the offset to center on when zooming into/out of this zone
         */
        fun zoneIndexToOffset(zoneIndex: Int, currentHourlyOffset: Int, zoom: ZoomLevel = ZoomLevel.WIDE): Int {
            return if (zoom == ZoomLevel.WIDE) {
                currentHourlyOffset + (-8 + 2 * zoneIndex)
            } else {
                val offsetFloat = -2f + (zoneIndex + 0.5f) / 3f
                currentHourlyOffset + Math.round(offsetFloat)
            }
        }
        private const val TAG = "WeatherWidgetProvider"
        const val EXTRA_INTERACTION_SOURCE = "com.weatherwidget.EXTRA_INTERACTION_SOURCE"

        /**
         * Update widget with loading state.
         */
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
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Main entry point for updating widget with data.
         * Routes to the appropriate handler based on the current view mode.
         */
        suspend fun updateWidgetWithData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            weatherList: List<ForecastEntity>,
            forecastSnapshots: Map<String, List<ForecastEntity>> = emptyMap(),
            hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
            currentTemps: List<com.weatherwidget.data.local.CurrentTempEntity> = emptyList(),
            dailyActualsBySource: DailyActualsBySource = emptyMap(),
            repository: com.weatherwidget.data.repository.WeatherRepository? = null,
            startupToken: String? = null,
        ) {
            val renderStartMs = SystemClock.elapsedRealtime()
            val stateManager = WidgetStateManager(context)
            val viewMode = stateManager.getViewMode(appWidgetId)
            Log.d(TAG, "updateWidgetInternal: widget=$appWidgetId viewMode=$viewMode zoom=${stateManager.getZoomLevel(appWidgetId)}")

            when (viewMode) {
                ViewMode.TEMPERATURE -> {
                    val now = LocalDateTime.now()
                    val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                    val centerTime = now.plusHours(hourlyOffset.toLong())
                    val targetDateStr = centerTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                    val targetPrecip =
                        weatherList
                            .find { it.targetDate == targetDateStr && it.source == displaySource.id }
                            ?.precipProbability
                    val observation = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
                    TemperatureViewHandler.updateWidget(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        hourlyForecasts = hourlyForecasts,
                        centerTime = centerTime,
                        displaySource = displaySource,
                        precipProbability = targetPrecip,
                        observedCurrentTemp = observation?.temperature,
                        observedCurrentTempFetchedAt = observation?.observedAt,
                        repository = repository,
                        startupToken = startupToken,
                        deferCurrentTempResolution = startupToken != null,
                    )
                }
                ViewMode.PRECIPITATION -> {
                    val now = LocalDateTime.now()
                    val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                    val centerTime = now.plusHours(hourlyOffset.toLong())
                    val targetDateStr = centerTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                    val targetPrecip =
                        weatherList
                            .find { it.targetDate == targetDateStr && it.source == displaySource.id }
                            ?.precipProbability
                    val observation = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
                    PrecipViewHandler.updateWidget(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        hourlyForecasts = hourlyForecasts,
                        centerTime = centerTime,
                        displaySource = displaySource,
                        precipProbability = targetPrecip,
                        observedCurrentTemp = observation?.temperature,
                        observedCurrentTempFetchedAt = observation?.observedAt,
                        repository = repository,
                        startupToken = startupToken,
                    )
                }
                ViewMode.CLOUD_COVER -> {
                    val now = LocalDateTime.now()
                    val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                    val centerTime = now.plusHours(hourlyOffset.toLong())
                    val targetDateStr = centerTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                    val targetPrecip =
                        weatherList
                            .find { it.targetDate == targetDateStr && it.source == displaySource.id }
                            ?.precipProbability
                    val observation = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
                    com.weatherwidget.widget.handlers.CloudCoverViewHandler.updateWidget(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        hourlyForecasts = hourlyForecasts,
                        centerTime = centerTime,
                        displaySource = displaySource,
                        precipProbability = targetPrecip,
                        observedCurrentTemp = observation?.temperature,
                        observedCurrentTempFetchedAt = observation?.observedAt,
                        repository = repository,
                        startupToken = startupToken,
                    )
                }
                ViewMode.DAILY -> {
                    DailyViewHandler.updateWidget(
                        context,
                        appWidgetManager,
                        appWidgetId,
                        weatherList,
                        forecastSnapshots,
                        hourlyForecasts,
                        currentTemps,
                        dailyActualsBySource,
                        repository,
                        startupToken = startupToken,
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
                    "view" to viewMode,
                    "hourlyCount" to hourlyForecasts.size,
                    "forecastCount" to weatherList.size,
                    "totalMs" to totalMs,
                ),
                debugTag = TAG,
            )
        }
    }
}
