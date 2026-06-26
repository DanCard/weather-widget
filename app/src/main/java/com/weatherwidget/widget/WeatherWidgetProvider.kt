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
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.TemperatureViewHandler
import com.weatherwidget.widget.handlers.PrecipViewHandler
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class StartupQueryResult(
        val weatherList: List<ForecastEntity>,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        val hourlyForecasts: List<HourlyForecastEntity>,
        val currentTemps: List<ObservationEntity>,
        val dailyActualsBySource: DailyActualsBySource,
        val forecastQueryMs: Long = 0L,
        val snapshotQueryMs: Long = 0L,
        val hourlyQueryMs: Long = 0L,
        val currentTempQueryMs: Long = 0L,
        val extremesQueryMs: Long = 0L,
    )

    @Inject
    lateinit var repository: WeatherRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        com.weatherwidget.WeatherWidgetApp.logFirstTriggerOnce("onUpdate")
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
        launchAsync {
            val dbOpenStartMs = SystemClock.elapsedRealtime()
            val database = WeatherDatabase.getDatabase(context)
            val dbOpenMs = SystemClock.elapsedRealtime() - dbOpenStartMs
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

            val latestWeatherStartMs = SystemClock.elapsedRealtime()
            val latestWeather = forecastDao.getLatestWeather()
            val latestWeatherMs = SystemClock.elapsedRealtime() - latestWeatherStartMs
            val stateManager = stateManager(context)
            val activeSources = filteredIds
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .map { widgetId ->
                    stateManager.getCurrentDisplaySource(widgetId).id
                }
                .toSet() + WeatherSource.GENERIC_GAP.id
            val activeSourceList = activeSources.toList()

            val widgetViewModes =
                filteredIds
                    .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                    .associateWith { stateManager.getViewMode(it) }
            val needsDailyData = needsDailyStartupData(widgetViewModes.values)
            appLogDao.log("WIDGET_LIFECYCLE", "phase=onUpdate_entry hasData=${latestWeather != null} count=${filteredIds.size} thread=${Thread.currentThread().name} sources=$activeSources")

            var staleCheckMs = 0L
            var queryResult: StartupQueryResult? = null

            try {
                if (latestWeather == null) {
                    for (appWidgetId in filteredIds) {
                        WidgetRenderer.updateWidgetLoading(context, appWidgetManager, appWidgetId)
                    }
                    triggerImmediateUpdate(context, reason = "on_update_no_data")
                } else {
                    // Immediate render of loading state to provide instant feedback
                    for (appWidgetId in filteredIds) {
                        WidgetRenderer.updateWidgetLoading(context, appWidgetManager, appWidgetId)
                    }

                    queryResult = loadStartupData(
                        forecastDao = forecastDao,
                        hourlyDao = hourlyDao,
                        latestWeather = latestWeather,
                        activeSourceList = activeSourceList,
                        needsDailyData = needsDailyData,
                    )
                    renderStartupWidgets(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        filteredIds = filteredIds,
                        result = queryResult,
                        startupToken = startupToken,
                    )
                    staleCheckMs = checkStalenessAndFetch(context)
                }
            } catch (e: CancellationException) {
                // HOURLY_PAINT_TRACE: a cancelled startup leaves the "Loading..." placeholder in place
                // (we deliberately do NOT repaint an error state on cancellation). Logged so a stuck
                // placeholder can be attributed to cancellation vs. a silent no-paint.
                appLogDao.log(
                    "HOURLY_PAINT_TRACE",
                    "phase=onUpdate_CANCELLED widgets=${filteredIds.joinToString(",")} msg=${e.message}",
                    "WARN",
                )
                throw e
            } catch (e: Exception) {
                // A throw here would otherwise leave every widget stuck on the "Loading..." placeholder
                // painted above. Repaint a recoverable error state, then rethrow so launchAsync logs it
                // (and Crashlytics records it once wired).
                Log.e(TAG, "onUpdate render failed; showing error fallback for ${filteredIds.size} widgets", e)
                for (appWidgetId in filteredIds) {
                    WidgetRenderer.updateWidgetError(context, appWidgetManager, appWidgetId)
                }
                throw e
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
                    "forecastMs" to (queryResult?.forecastQueryMs ?: 0L),
                    "snapshotsMs" to (queryResult?.snapshotQueryMs ?: 0L),
                    "hourlyMs" to (queryResult?.hourlyQueryMs ?: 0L),
                    "currentTempMs" to (queryResult?.currentTempQueryMs ?: 0L),
                    "extremesMs" to (queryResult?.extremesQueryMs ?: 0L),
                    "staleCheckMs" to staleCheckMs,
                    "totalMs" to totalMs,
                    "dbEvent" to latestDbLifecycle?.tag,
                ),
                debugTag = TAG,
            )
        }
    }

    private suspend fun loadStartupData(
        forecastDao: ForecastDao,
        hourlyDao: HourlyForecastDao,
        latestWeather: ForecastEntity,
        activeSourceList: List<String>,
        needsDailyData: Boolean,
    ): StartupQueryResult = coroutineScope {
        val today = LocalDate.now()
        val historyStart = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val thirtyDays = today.plusDays(7).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastSnapshotStart = today.minusDays(3).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastSnapshotEnd = today.minusDays(2).toEpochDay() * WidgetConstants.MS_IN_A_DAY

        val weatherListDeferred = async {
            forecastDao.getForecastsInRangeForSources(
                historyStart,
                thirtyDays,
                latestWeather.locationLat,
                latestWeather.locationLon,
                activeSourceList
            )
        }

        val forecastSnapshotsDeferred = async {
            if (needsDailyData) {
                val pastSnapshots = forecastDao.getLatestForecastsInRangeForSources(
                    pastSnapshotStart,
                    pastSnapshotEnd,
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    activeSourceList,
                )
                val recentSnapshots = forecastDao.getAllForecastsInRangeForSources(
                    historyStart,
                    thirtyDays,
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    activeSourceList,
                )
                (pastSnapshots + recentSnapshots)
                    .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
            } else {
                emptyMap()
            }
        }

        val nowLocal = LocalDateTime.now()
        val zoneId = ZoneId.systemDefault()
        val hourlyStart = nowLocal.minusHours(HOURLY_LOOKBACK_HOURS).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val hourlyEnd = nowLocal.plusHours(HOURLY_GRAPH_LOOKAHEAD_HOURS).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()

        val hourlyForecastsDeferred = async {
            hourlyDao.getHourlyForecasts(
                hourlyStart,
                hourlyEnd,
                latestWeather.locationLat,
                latestWeather.locationLon,
            )
        }

        val currentTempsDeferred = async {
            val querySinceMs = nowLocal.minusHours(HOURLY_LOOKBACK_HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.getMainObservationsWithComputedNwsBlend(
                latestWeather.locationLat,
                latestWeather.locationLon,
                querySinceMs,
            )
        }

        val dailyActualsDeferred = async {
            if (needsDailyData) {
                repository.getDailyActualsWithLiveToday(
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    hourlyForecasts = hourlyForecastsDeferred.await(),
                    activeSourceList = activeSourceList
                )
            } else {
                emptyMap()
            }
        }

        val forecastQueryStartMs = SystemClock.elapsedRealtime()
        val weatherList = weatherListDeferred.await()
        val forecastQueryMs = SystemClock.elapsedRealtime() - forecastQueryStartMs
        Log.d(TAG, "loadStartupData: forecastQueryMs=$forecastQueryMs count=${weatherList.size}")

        val snapshotQueryStartMs = SystemClock.elapsedRealtime()
        val forecastSnapshots = forecastSnapshotsDeferred.await()
        val snapshotQueryMs = SystemClock.elapsedRealtime() - snapshotQueryStartMs
        Log.d(TAG, "loadStartupData: snapshotQueryMs=$snapshotQueryMs count=${forecastSnapshots.size}")

        val hourlyQueryStartMs = SystemClock.elapsedRealtime()
        val hourlyForecasts = hourlyForecastsDeferred.await()
        val hourlyQueryMs = SystemClock.elapsedRealtime() - hourlyQueryStartMs
        Log.d(TAG, "loadStartupData: hourlyQueryMs=$hourlyQueryMs count=${hourlyForecasts.size}")

        val currentTempQueryStartMs = SystemClock.elapsedRealtime()
        val currentTemps = currentTempsDeferred.await()
        val currentTempQueryMs = SystemClock.elapsedRealtime() - currentTempQueryStartMs
        Log.d(TAG, "loadStartupData: currentTempQueryMs=$currentTempQueryMs count=${currentTemps.size}")

        val extremesQueryStartMs = SystemClock.elapsedRealtime()
        val dailyActualsBySource = dailyActualsDeferred.await()
        val extremesQueryMs = SystemClock.elapsedRealtime() - extremesQueryStartMs
        Log.d(TAG, "loadStartupData: extremesQueryMs=$extremesQueryMs count=${dailyActualsBySource.size}")

        StartupQueryResult(
            weatherList = weatherList,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActualsBySource = dailyActualsBySource,
            forecastQueryMs = forecastQueryMs,
            snapshotQueryMs = snapshotQueryMs,
            hourlyQueryMs = hourlyQueryMs,
            currentTempQueryMs = currentTempQueryMs,
            extremesQueryMs = extremesQueryMs,
        )
    }

    private suspend fun renderStartupWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        filteredIds: IntArray,
        result: StartupQueryResult,
        startupToken: String,
    ) = coroutineScope {
        val stateManager = stateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        for (appWidgetId in filteredIds) {
            // HOURLY_PAINT_TRACE: persisted (survives process freeze) lifecycle of each startup paint.
            // Diagnoses widgets stuck on the "Loading..." placeholder — a paint that is cancelled (e.g.
            // a second UI_PAINT job cancels this one via WidgetUpdateTracker) never reaches its
            // updateAppWidget call and never hits onUpdate's error repaint, so the placeholder persists.
            val viewMode = stateManager.getViewMode(appWidgetId)
            appLogDao.log(
                "HOURLY_PAINT_TRACE",
                "phase=startup_launch widget=$appWidgetId view=$viewMode token=$startupToken",
            )
            val job = launch {
                try {
                    WidgetRenderer.updateWidgetWithData(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        weatherList = result.weatherList,
                        forecastSnapshots = result.forecastSnapshots,
                        hourlyForecasts = result.hourlyForecasts,
                        currentTemps = result.currentTemps,
                        dailyActualsBySource = result.dailyActualsBySource,
                        repository = repository,
                        startupToken = startupToken,
                    )
                    appLogDao.log(
                        "HOURLY_PAINT_TRACE",
                        "phase=startup_done widget=$appWidgetId view=$viewMode",
                    )
                } catch (e: CancellationException) {
                    appLogDao.log(
                        "HOURLY_PAINT_TRACE",
                        "phase=startup_CANCELLED widget=$appWidgetId view=$viewMode msg=${e.message}",
                        "WARN",
                    )
                    Log.w(TAG, "startup paint CANCELLED widget=$appWidgetId view=$viewMode", e)
                    throw e
                } catch (e: Exception) {
                    appLogDao.log(
                        "HOURLY_PAINT_TRACE",
                        "phase=startup_ERROR widget=$appWidgetId view=$viewMode msg=${e.message}",
                        "ERROR",
                    )
                    Log.e(TAG, "startup paint ERROR widget=$appWidgetId view=$viewMode", e)
                    throw e
                }
            }
            WidgetUpdateTracker.trackJob(appWidgetId, job, WidgetUpdateTracker.JobType.UI_PAINT)
        }
    }

    private suspend fun checkStalenessAndFetch(context: Context): Long {
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
        return SystemClock.elapsedRealtime() - staleCheckStartMs
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
        val stateManager = stateManager(context)
        for (appWidgetId in appWidgetIds) {
            stateManager.clearWidgetState(appWidgetId)
            lastUpdateByWidgetId.remove(appWidgetId)
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: action=${intent.action}")
        com.weatherwidget.WeatherWidgetApp.logFirstTriggerOnce("onReceive:${intent.action}")

        when (intent.action) {
            WidgetActions.ACTION_REFRESH -> handleRefreshAction(context, intent)
            WidgetActions.ACTION_NAV_LEFT, WidgetActions.ACTION_NAV_RIGHT -> handleNavigationAction(context, intent)
            WidgetActions.ACTION_TOGGLE_API -> handleToggleApiAction(context, intent)
            WidgetActions.ACTION_TOGGLE_VIEW -> handleToggleViewAction(context, intent)
            WidgetActions.ACTION_TOGGLE_PRECIP -> handleTogglePrecipAction(context, intent)
            WidgetActions.ACTION_CYCLE_ZOOM -> handleCycleZoomAction(context, intent)
            WidgetActions.ACTION_SET_VIEW -> handleSetViewAction(context, intent)
            WidgetActions.ACTION_DAY_CLICK -> {
                Log.d(TAG, "onReceive: ACTION_DAY_CLICK extras: date=${intent.getStringExtra("date")} index=${intent.getIntExtra("index", -1)} targetView=${intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW)} offset=${intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE)} widget=${getWidgetId(intent)}")
                handleDayClickAction(context, intent)
            }
            WidgetActions.ACTION_SHOW_TOAST -> handleShowToastAction(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> triggerUiOnlyUpdate(context, reason = "package_replaced")
        }
    }

    // Called from onReceive on the main thread; Toast requires main thread.
    private fun handleShowToastAction(context: Context, intent: Intent) {
        val message = intent.getStringExtra(WidgetActions.EXTRA_TOAST_MESSAGE) ?: "No additional data"
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
        val showHistory = intent.getBooleanExtra("showHistory", isHistory)
        val targetViewName = intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW) ?: "PRECIPITATION"
        val targetOffset = intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, 0)
        val clickSource = intent.getStringExtra(WidgetActions.EXTRA_CLICK_SOURCE) ?: "unknown"
        Log.d(TAG, "handleDayClickAction: widget=$appWidgetId, date=$dateStr, isHistory=$isHistory, showHistory=$showHistory, index=$index, targetView=$targetViewName, hourlyOffset=$targetOffset, clickSource=$clickSource")

        val receiveTimeMs = System.currentTimeMillis()
        launchAsync {
            val coroutineStartMs = System.currentTimeMillis()
            val database = WeatherDatabase.getDatabase(context)
            database.appLogDao().log("CLICK_DAILY", "index=$index, date=$dateStr, isHistory=$isHistory, showHistory=$showHistory, targetView=$targetViewName, offset=$targetOffset, clickSource=$clickSource")

            if (showHistory) {
                navigateToHistory(context, intent, appWidgetId, dateStr, database, receiveTimeMs, coroutineStartMs)
            } else {
                navigateToHourlyView(context, intent, appWidgetId, dateStr, database, receiveTimeMs, coroutineStartMs, targetViewName, targetOffset)
            }
        }
    }

    private suspend fun navigateToHistory(
        context: Context,
        intent: Intent,
        appWidgetId: Int,
        dateStr: String,
        database: WeatherDatabase,
        receiveTimeMs: Long,
        coroutineStartMs: Long,
    ) {
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
    }

    private suspend fun navigateToHourlyView(
        context: Context,
        intent: Intent,
        appWidgetId: Int,
        dateStr: String,
        database: WeatherDatabase,
        receiveTimeMs: Long,
        coroutineStartMs: Long,
        targetViewName: String,
        targetOffset: Int,
    ) {
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
        Log.d(TAG, "handleDayClickAction branch: hasHourlyData=$hasHourlyData targetMode=$targetMode date=$dateStr offset=$targetOffset")
        if (!hasHourlyData && (targetMode == ViewMode.PRECIPITATION || targetMode == ViewMode.TEMPERATURE || targetMode == ViewMode.CLOUD_COVER)) {
            Log.w(TAG, "handleDayClickAction: NO hourly data for date=$dateStr mode=$targetMode -> opening settings")
            database.appLogDao().log(
                "CLICK_DAILY_NO_HOURLY",
                "date=$dateStr mode=$targetMode -> settings",
            )
            val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
        } else {
            val stateManager = stateManager(context)
            // Day-click navigates the hourly view by jumping the hourly offset to the clicked day
            // (handleSetView below). The window then follows the single rolling/anchor rule in
            // resolveHourlyCenterTime: it rolls while NOW is in-window (today) and is pinned to a fixed
            // absolute anchor once NOW falls outside it (past/future days). No separate single-day pin —
            // that rigid 00:00->24:00 grid used to extend past the loaded forecast horizon and feed NaN
            // temps into the label renderer (renderGraph crash -> stuck "Loading...").
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
        
        val hasForecasts = if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            hourlyForDay.isNotEmpty()
        } else {
            val displaySource = stateManager(context).getCurrentDisplaySource(appWidgetId).id
            hourlyForDay.any { it.source == displaySource || it.source == WeatherSource.GENERIC_GAP.id }
        }

        if (hasForecasts) return true

        // If no hourly forecasts exist, check for observations if the date is in the past.
        // The hourly graph can render a curve from observations alone.
        if (targetDate.isBefore(LocalDate.now())) {
            val observations = database.observationDao().getObservationsInRange(startMs, endMs, effectiveLat, effectiveLon)
            return observations.isNotEmpty()
        }

        return false
    }

    private fun handleRefreshAction(
        context: Context,
        intent: Intent,
    ) {
        val uiOnly = intent.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false)
        Log.d(TAG, "onReceive: Refresh triggered (uiOnly=$uiOnly)")

        launchAsync {
            UIUpdateScheduler(context).scheduleNextUpdate()
            
            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging, powerManager.isInteractive)) {
                CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context, isScreenInteractive = powerManager.isInteractive)
            }

            val isDataStale = DataFreshness.isDataStale(context)
            val freshnessSummary = DataFreshness.getVisibleSourceFreshnessSummary(context)
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "REFRESH_DECISION",
                "uiOnlyRequested=$uiOnly charging=$isCharging interactive=${powerManager.isInteractive} " +
                    "isDataStale=$isDataStale $freshnessSummary",
                "INFO",
            )
            val needsNetworkFetch = !uiOnly && WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)

            if (!needsNetworkFetch) {
                triggerUiOnlyUpdate(context, reason = "refresh_action_ui_only")
                Log.d(TAG, "onReceive: UI-only refresh path (uiOnly=$uiOnly, stale=$isDataStale)")
            } else {
                // Repaint every widget from cache right now so the refresh shows current cached data in
                // ~0.5s instead of waiting on the forced network sync (~7s). This is a direct render
                // (not a second worker) so its DB read completes before the fetch below starts writing
                // — a UI-only worker raced the fetch's writes and still painted ~5s late.
                WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)

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
            val isLeft = intent.action == WidgetActions.ACTION_NAV_LEFT
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
            val interactionSource = intent.getStringExtra(WidgetActions.EXTRA_INTERACTION_SOURCE) ?: "unknown"
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
        val zoomCenterOffset = if (intent.hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET)) {
            intent.getIntExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, 0)
        } else {
            null
        }
        val stateManager = stateManager(context)
        val currentMode = stateManager.getViewMode(appWidgetId)
        val currentZoom = stateManager.getZoomLevel(appWidgetId)
        Log.d(TAG, "handleCycleZoomAction: widget=$appWidgetId centerOffset=$zoomCenterOffset currentMode=$currentMode currentZoom=$currentZoom")
        if (currentMode == ViewMode.DAILY) {
            Log.e(TAG, "BUG: CYCLE_ZOOM fired while in DAILY mode! This should be ACTION_DAY_CLICK. Extras: ${intent.extras}")
            return
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
        val targetViewName = intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW) ?: ""
        val targetOffset = intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE)
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
        val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging, powerManager.isInteractive)) {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context, isScreenInteractive = powerManager.isInteractive)
        }
    }

    private fun getWidgetId(intent: Intent): Int =
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    private fun stateManager(context: Context) = WidgetStateManager(context)

    private fun launchAsync(block: suspend CoroutineScope.() -> Unit): Job {
        val pendingResult = goAsync()
        return scope.launch {
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


    companion object {
        /** Hours of past hourly data to query — covers yesterday's actuals for rain analysis. */
        const val HOURLY_LOOKBACK_HOURS = 72L
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

        private val lastUpdateByWidgetId = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private const val STARTUP_DEBOUNCE_MS = 500L

        /**
         * Enqueue the periodic forecast worker. Interval is charging/battery-aware via
         * [ForecastFetchPolicy.periodicTickMinutes]. Safe to call repeatedly — uses
         * ExistingPeriodicWorkPolicy.UPDATE so the interval refreshes on each call.
         */
        @JvmStatic
        internal fun schedulePeriodicUpdate(context: Context) {
            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
            val tickMinutes = ForecastFetchPolicy.periodicTickMinutes(isCharging, batteryLevel)

            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<WeatherWidgetWorker>(
                    tickMinutes,
                    TimeUnit.MINUTES,
                )
                    .setInputData(
                        Data.Builder()
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "periodic_${tickMinutes}m")
                            .build()
                    )
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest,
            )
            val nextWindowStartMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(tickMinutes)
            Log.d(TAG, "PERIODIC_REFRESH_SCHEDULE: name=$WORK_NAME intervalMinutes=$tickMinutes charging=$isCharging battery=$batteryLevel policy=update nextWindowStartMs=$nextWindowStartMs")
        }


        const val HOUR_ZONE_COUNT = 13
        private const val STARTUP_STALE_REFRESH_DELAY_MS = 1_500L

        internal fun needsDailyStartupData(viewModes: Collection<ViewMode>): Boolean =
            viewModes.any { it == ViewMode.DAILY }

        internal fun triggerUiOnlyUpdate(context: Context, reason: String = "unspecified") {
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
            // 13 zones span the full rendered window (back+forward hours). Zone 6 is the visual
            // (pixel) center, which for an asymmetric window sits (forward-back)/2 hours off the
            // window center. This generalizes the old symmetric WIDE (2h/zone) and NARROW
            // (1/3h/zone) cases and handles history-leaning levels like THREE_DAY (48 back/24 fwd:
            // zone 0 -> -48h, zone 12 -> +24h).
            val perZoneHours = (zoom.backHours + zoom.forwardHours) / 12f
            val asymmetryShift = (zoom.forwardHours - zoom.backHours) / 2f
            return currentHourlyOffset + (perZoneHours * (zoneIndex - 6) + asymmetryShift).roundToInt()
        }
        private const val TAG = "WeatherWidgetProvider"


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
