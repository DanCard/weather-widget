package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.getForecastsInRangeForSources
import com.weatherwidget.data.local.getLatestForecastsInRangeForSources
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.handlers.DailyLoadWindowResolver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

/** Loads and paints the shared startup snapshot for an AppWidgetProvider update batch. */
internal class WidgetStartupCoordinator(
    private val repository: WeatherRepository,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val operationTimer = StartupOperationTimer(elapsedRealtime)

    private data class StartupQueryResult(
        val weatherList: List<ForecastEntity>,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        val hourlyForecasts: List<HourlyForecastEntity>,
        val currentTemps: List<ObservationEntity>,
        val dailyActualsBySource: DailyActualsBySource,
        val forecastQueryMs: Long,
        val forecastGapFillMs: Long,
        val snapshotQueryMs: Long,
        val snapshotGapFillMs: Long,
        val hourlyQueryMs: Long,
        val currentTempQueryMs: Long,
        val extremesQueryMs: Long,
    )

    suspend fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        startupToken: String,
        onUpdateStartMs: Long,
    ) {
        val dbOpenStartMs = elapsedRealtime()
        val database = WeatherDatabase.getDatabase(context)
        val dbOpenMs = elapsedRealtime() - dbOpenStartMs
        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val appLogDao = database.appLogDao()
        val gapFiller = ClimateGapFiller(database.climateNormalDao())
        val latestDbLifecycle = appLogDao.getLatestDatabaseLifecycleEvent()
        WidgetPerfLogger.logIfSlow(
            appLogDao = appLogDao,
            thresholdMs = WidgetPerfLogger.DB_OPEN_SLOW_MS,
            totalMs = dbOpenMs,
            appLogTag = WidgetPerfLogger.TAG_DB_OPEN_PERF,
            message =
                WidgetPerfLogger.kv(
                    "token" to startupToken,
                    "phase" to "onUpdate",
                    "dbOpenMs" to dbOpenMs,
                    "dbEvent" to latestDbLifecycle?.tag,
                    "dbEventTs" to latestDbLifecycle?.timestamp,
                ),
            debugTag = TAG,
        )

        val latestWeatherStartMs = elapsedRealtime()
        val latestWeather = forecastDao.getLatestWeather()
        val latestWeatherMs = elapsedRealtime() - latestWeatherStartMs
        val stateManager = WidgetStateManager(context)
        val validWidgetIds =
            appWidgetIds.filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }.toIntArray()
        val activeSources =
            validWidgetIds
                .map { stateManager.getCurrentDisplaySource(it).id }
                .toSet() + WeatherSource.GENERIC_GAP.id
        val widgetViewModes =
            validWidgetIds.associateWith { stateManager.getViewMode(it) }
        val needsDailyData = widgetViewModes.values.any { it == ViewMode.DAILY }
        appLogDao.log(
            "WIDGET_LIFECYCLE",
            "phase=onUpdate_entry hasData=${latestWeather != null} count=${validWidgetIds.size} " +
                "thread=${Thread.currentThread().name} sources=$activeSources",
        )

        var staleCheckMs = 0L
        var queryResult: StartupQueryResult? = null
        try {
            if (latestWeather == null) {
                validWidgetIds.forEach { appWidgetId ->
                    WidgetRenderer.updateWidgetLoading(
                        context,
                        appWidgetManager,
                        appWidgetId,
                        origin = WidgetPushDispatcher.Origin.PROVIDER_ON_UPDATE,
                    )
                }
                WidgetWorkScheduler.enqueueRedundantImmediateSync(
                    context,
                    reason = "on_update_no_data",
                )
            } else {
                queryResult =
                    loadStartupData(
                        forecastDao = forecastDao,
                        hourlyDao = hourlyDao,
                        latestWeather = latestWeather,
                        activeSourceList = activeSources.toList(),
                        needsDailyData = needsDailyData,
                        gapFiller = gapFiller,
                        loadWindow = DailyLoadWindowResolver.resolve(context),
                    )
                renderStartupWidgets(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = validWidgetIds,
                    result = queryResult,
                    startupToken = startupToken,
                    appLogDao = appLogDao,
                )
                staleCheckMs = checkStalenessAndFetch(context)
            }
        } catch (e: CancellationException) {
            appLogDao.log(
                "HOURLY_PAINT_TRACE",
                "phase=onUpdate_CANCELLED widgets=${validWidgetIds.joinToString(",")} msg=${e.message}",
                "WARN",
            )
            throw e
        } catch (e: Exception) {
            if (latestWeather == null) {
                Log.e(
                    TAG,
                    "onUpdate render failed; showing error fallback for ${validWidgetIds.size} widgets",
                    e,
                )
                validWidgetIds.forEach { appWidgetId ->
                    WidgetRenderer.updateWidgetError(
                        context,
                        appWidgetManager,
                        appWidgetId,
                        origin = WidgetPushDispatcher.Origin.PROVIDER_ON_UPDATE,
                    )
                }
            } else {
                Log.e(
                    TAG,
                    "onUpdate render failed; keeping cached content for ${validWidgetIds.size} widgets",
                    e,
                )
            }
            throw e
        }

        WidgetWorkScheduler.schedulePeriodicSync(context)
        val totalMs = elapsedRealtime() - onUpdateStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = appLogDao,
            thresholdMs = WidgetPerfLogger.STARTUP_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_WIDGET_STARTUP_PERF,
            message =
                WidgetPerfLogger.kv(
                    "token" to startupToken,
                    "widgets" to validWidgetIds.size,
                    "dbOpenMs" to dbOpenMs,
                    "latestWeatherMs" to latestWeatherMs,
                    "forecastMs" to (queryResult?.forecastQueryMs ?: 0L),
                    "forecastGapFillMs" to (queryResult?.forecastGapFillMs ?: 0L),
                    "snapshotsMs" to (queryResult?.snapshotQueryMs ?: 0L),
                    "snapshotGapFillMs" to (queryResult?.snapshotGapFillMs ?: 0L),
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

    private suspend fun loadStartupData(
        forecastDao: ForecastDao,
        hourlyDao: HourlyForecastDao,
        latestWeather: ForecastEntity,
        activeSourceList: List<String>,
        needsDailyData: Boolean,
        gapFiller: ClimateGapFiller,
        // Query only what some installed widget actually renders (see DailyLoadWindowResolver). The
        // gap-fill horizon stays at the full navigation horizon: those rows are synthesized in
        // memory from 12 cached monthly means, not queried.
        loadWindow: NavigationUtils.DailyLoadWindow,
    ): StartupQueryResult = coroutineScope {
        val today = LocalDate.now()
        val nowLocal = LocalDateTime.now()
        val zoneId = ZoneId.systemDefault()
        val historyStart = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val horizonEnd =
            today.plusDays(loadWindow.forecastDays).toEpochDay() *
                WidgetConstants.MS_IN_A_DAY
        val pastSnapshotStart = today.minusDays(3).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastSnapshotEnd = today.minusDays(2).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val hourlyStart =
            nowLocal
                .minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)
                .truncatedTo(ChronoUnit.HOURS)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val hourlyEnd =
            nowLocal
                .plusHours(WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS)
                .truncatedTo(ChronoUnit.HOURS)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()

        val weatherDeferred =
            operationTimer.async(this) {
                forecastDao.getForecastsInRangeForSources(
                    historyStart,
                    horizonEnd,
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    activeSourceList,
                )
            }
        val snapshotsDeferred =
            operationTimer.async(this) {
                if (!needsDailyData) {
                    emptyList()
                } else {
                    val past =
                        forecastDao.getLatestForecastsInRangeForSources(
                            pastSnapshotStart,
                            pastSnapshotEnd,
                            latestWeather.locationLat,
                            latestWeather.locationLon,
                            activeSourceList,
                        )
                    val recent =
                        forecastDao.getAllForecastsInRangeForSources(
                            historyStart,
                            horizonEnd,
                            latestWeather.locationLat,
                            latestWeather.locationLon,
                            activeSourceList,
                        )
                    past + recent
                }
            }
        val hourlyDeferred =
            operationTimer.async(this) {
                // Same source restriction the daily query above already uses: activeSourceList is
                // every widget's display source plus GENERIC_GAP. Unfiltered this returned every
                // source ever fetched, for consumers that filter to the display source anyway.
                hourlyDao.getHourlyForecastsForSources(
                    hourlyStart,
                    hourlyEnd,
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    activeSourceList,
                )
            }
        val currentTempsDeferred =
            operationTimer.async(this) {
                repository.getMainObservationsWithComputedNwsBlend(
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    nowLocal
                        .minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                )
            }
        val weather = weatherDeferred.await()
        val gapFillStartMs = elapsedRealtime()
        val weatherWithGaps =
            gapFiller.appendGaps(
                weather.value,
                latestWeather.locationLat,
                latestWeather.locationLon,
                today,
                horizonDays = WidgetQueryWindows.DAILY_FORECAST_DAYS,
            )
        val gapFillMs = elapsedRealtime() - gapFillStartMs
        val snapshotRows = snapshotsDeferred.await()
        val snapshotGapFillStartMs = elapsedRealtime()
        val snapshots =
            if (!needsDailyData) {
                emptyMap()
            } else {
                gapFiller.appendGapsToSnapshots(
                    snapshotRows.value.groupBy {
                        LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY)
                    },
                    latestWeather.locationLat,
                    latestWeather.locationLon,
                    today,
                    horizonDays = WidgetQueryWindows.DAILY_FORECAST_DAYS,
                )
            }
        val snapshotGapFillMs = elapsedRealtime() - snapshotGapFillStartMs
        val hourly = hourlyDeferred.await()
        val dailyActualsDeferred =
            operationTimer.async(this) {
                if (!needsDailyData) {
                    emptyMap()
                } else {
                    repository.getDailyActualsWithLiveToday(
                        latestWeather.locationLat,
                        latestWeather.locationLon,
                        hourlyForecasts = hourly.value,
                        activeSourceList = activeSourceList,
                    )
                }
            }
        val currentTemps = currentTempsDeferred.await()
        val dailyActuals = dailyActualsDeferred.await()

        Log.d(
            TAG,
            "loadStartupData: forecastQueryMs=${weather.durationMs} gapFillMs=$gapFillMs " +
                "snapshotsMs=${snapshotRows.durationMs} snapshotGapFillMs=$snapshotGapFillMs " +
                "hourlyMs=${hourly.durationMs} " +
                "currentTempMs=${currentTemps.durationMs} extremesMs=${dailyActuals.durationMs}",
        )
        StartupQueryResult(
            weatherList = weatherWithGaps,
            forecastSnapshots = snapshots,
            hourlyForecasts = hourly.value,
            currentTemps = currentTemps.value,
            dailyActualsBySource = dailyActuals.value,
            forecastQueryMs = weather.durationMs,
            forecastGapFillMs = gapFillMs,
            snapshotQueryMs = snapshotRows.durationMs,
            snapshotGapFillMs = snapshotGapFillMs,
            hourlyQueryMs = hourly.durationMs,
            currentTempQueryMs = currentTemps.durationMs,
            extremesQueryMs = dailyActuals.durationMs,
        )
    }

    private suspend fun renderStartupWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        result: StartupQueryResult,
        startupToken: String,
        appLogDao: AppLogDao,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewModes = appWidgetIds.associateWith(stateManager::getViewMode)
        val outcomes =
            runStartupTasksIsolated(
                appWidgetIds = appWidgetIds,
                onStarted = { appWidgetId, job ->
                    WidgetUpdateTracker.trackJob(
                        appWidgetId,
                        job,
                        WidgetUpdateTracker.JobType.UI_PAINT,
                    )
                },
            ) { appWidgetId ->
                val viewMode = stateManager.getViewMode(appWidgetId)
                appLogDao.log(
                    "HOURLY_PAINT_TRACE",
                    "phase=startup_launch widget=$appWidgetId view=$viewMode token=$startupToken",
                )
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
                        origin = WidgetPushDispatcher.Origin.PROVIDER_ON_UPDATE,
                    )
                    appLogDao.log(
                        "HOURLY_PAINT_TRACE",
                        "phase=startup_done widget=$appWidgetId view=$viewMode",
                    )
                    appLogDao.log(
                        "WIDGET_RENDER_OK",
                        "widget=$appWidgetId view=$viewMode path=onUpdate token=$startupToken",
                    )
                } catch (e: CancellationException) {
                    appLogDao.log(
                        "HOURLY_PAINT_TRACE",
                        "phase=startup_CANCELLED widget=$appWidgetId view=$viewMode msg=${e.message}",
                        "WARN",
                    )
                    throw e
                }
            }
        outcomes.filter { it.failure != null }.forEach { outcome ->
            val viewMode = viewModes.getValue(outcome.appWidgetId)
            runCatching {
                appLogDao.log(
                    "HOURLY_PAINT_TRACE",
                    "phase=startup_ERROR widget=${outcome.appWidgetId} view=$viewMode " +
                        "msg=${outcome.failure?.message}",
                    "ERROR",
                )
            }
            Log.e(
                TAG,
                "startup paint ERROR widget=${outcome.appWidgetId} view=$viewMode",
                outcome.failure,
            )
        }
        val failed = outcomes.filter { it.failure != null }.map { it.appWidgetId }
        val succeeded = outcomes.filter { it.failure == null }.map { it.appWidgetId }
        appLogDao.log(
            "WIDGET_STARTUP_BATCH",
            "token=$startupToken succeeded=${succeeded.joinToString(",")} " +
                "failed=${failed.joinToString(",")}",
            if (failed.isEmpty()) "DEBUG" else "WARN",
        )
    }

    private suspend fun checkStalenessAndFetch(context: Context): Long {
        val startMs = elapsedRealtime()
        if (DataFreshness.isDataStale(context)) {
            val dataAgeMinutes = DataFreshness.getDataAgeMinutes(context)
            val delayMs = StartupFetchPolicy.primaryFetchDelayMs(dataAgeMinutes)
            Log.d(
                TAG,
                "Data stale ageMinutes=$dataAgeMinutes; delayed startup fetch ${delayMs}ms",
            )
            WidgetWorkScheduler.enqueueDelayedStartupSync(
                context = context,
                reason = "on_update_stale",
                initialDelayMs = delayMs,
            )
        } else {
            Log.d(TAG, "Data is fresh; startup fetch skipped")
        }
        return elapsedRealtime() - startMs
    }

    private companion object {
        const val TAG = "WidgetStartupCoordinator"
    }
}
