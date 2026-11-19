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
    private val startupCooldown: () -> StartupCooldown = { com.weatherwidget.WeatherWidgetApp.startupCooldown() },
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
        /** The provisional (stored-rows) actuals read; the live blend is [LiveActualsPass]. */
        val extremesQueryMs: Long,
    )

    /** The second pass: the live blend, and the widgets it repainted. */
    private data class LiveActualsPass(val durationMs: Long, val repainted: IntArray, val changedSources: Set<String>)

    suspend fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        startupToken: String,
        onUpdateStartMs: Long,
        /** Who is painting, for [StartupPaintClaims]: `onUpdate` or the package-replaced rebind. */
        claimVia: String = STARTUP_CLAIM_VIA,
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

        // One result, not three vars assigned inside the try (the conditionally assigned `long`
        // shape whose dex ART rejected in CloudCoverViewHandler; plans/261119-split-oversized-android-suspend-functions.md).
        val paint = try {
            if (latestWeather == null) {
                paintWithoutData(context, appWidgetManager, validWidgetIds, stateManager, forecastDao, appLogDao)
                StartupPaint.NONE
            } else {
                paintFromCache(
                    context, appWidgetManager, validWidgetIds, claimVia, appLogDao, database, forecastDao,
                    hourlyDao, latestWeather, activeSources, needsDailyData, gapFiller, stateManager, startupToken,
                )
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
                    "forecastMs" to (paint.queryResult?.forecastQueryMs ?: 0L),
                    "forecastGapFillMs" to (paint.queryResult?.forecastGapFillMs ?: 0L),
                    "snapshotsMs" to (paint.queryResult?.snapshotQueryMs ?: 0L),
                    "snapshotGapFillMs" to (paint.queryResult?.snapshotGapFillMs ?: 0L),
                    "hourlyMs" to (paint.queryResult?.hourlyQueryMs ?: 0L),
                    "currentTempMs" to (paint.queryResult?.currentTempQueryMs ?: 0L),
                    "extremesMs" to (paint.queryResult?.extremesQueryMs ?: 0L),
                    "liveExtremesMs" to (paint.livePass?.durationMs ?: 0L),
                    "liveRepainted" to (paint.livePass?.repainted?.joinToString(",") ?: ""),
                    "liveChanged" to (paint.livePass?.changedSources?.sorted()?.joinToString(",") ?: ""),
                    "staleCheckMs" to paint.staleCheckMs,
                    "totalMs" to totalMs,
                    "dbEvent" to latestDbLifecycle?.tag,
                ),
            debugTag = TAG,
        )
    }

    /** What the startup paint reports to the perf log: the cache query, the live actuals pass, the stale check. */
    private class StartupPaint(val queryResult: StartupQueryResult?, val livePass: LiveActualsPass?, val staleCheckMs: Long) {
        companion object {
            val NONE = StartupPaint(null, null, 0L)
        }
    }

    /** No cached weather: show Loading and sync, or say there is no location. */
    private suspend fun paintWithoutData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        validWidgetIds: IntArray,
        stateManager: WidgetStateManager,
        forecastDao: ForecastDao,
        appLogDao: AppLogDao,
    ) {
        // No data *and* no location is a settled state, not a wait. The sync we would enqueue
        // has nothing to fetch and returns without painting, so "Loading..." would stick until
        // the user happened to open the app — the same stranded-placeholder failure this
        // coordinator guards against elsewhere. Say what is actually wrong instead.
        //
        // Note this skips only the placeholder and the immediate sync: schedulePeriodicSync
        // below must still run, because the periodic full sync is what carries the device
        // sampling that eventually rescues this widget.
        val hasLocation =
            ActiveLocationResolver.resolve(context, stateManager, forecastDao) != null
        if (hasLocation) {
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
            appLogDao.log(
                "NO_LOCATION",
                "reason=on_update_no_data widgets=${validWidgetIds.size} action=render_error_skip_fetch",
                "INFO",
            )
            validWidgetIds.forEach { appWidgetId ->
                WidgetRenderer.updateWidgetNoLocation(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    origin = WidgetPushDispatcher.Origin.PROVIDER_ON_UPDATE,
                )
            }
        }
    }

    /** Cached weather: paint each unclaimed widget from it, run the live actuals pass, then the stale check. */
    private suspend fun paintFromCache(
        context: Context,
        appWidgetManager: AppWidgetManager,
        validWidgetIds: IntArray,
        claimVia: String,
        appLogDao: AppLogDao,
        database: WeatherDatabase,
        forecastDao: ForecastDao,
        hourlyDao: HourlyForecastDao,
        latestWeather: ForecastEntity,
        activeSources: Set<String>,
        needsDailyData: Boolean,
        gapFiller: ClimateGapFiller,
        stateManager: WidgetStateManager,
        startupToken: String,
    ): StartupPaint {
        var queryResult: StartupQueryResult? = null
        var livePass: LiveActualsPass? = null
        // One startup cache paint per widget: PackageReplacedReceiver may already have
        // painted these seconds ago (see StartupPaintClaims). Painting them again costs a
        // second cold full render and shows nothing new; a repeat of this same trigger
        // (a host re-add, e.g. a Fold unfold) still paints. The staleness check and the
        // periodic schedule below are this path's own job and still run.
        val unpaintedIds = claimStartupPaints(validWidgetIds, claimVia, appLogDao)
        if (unpaintedIds.isNotEmpty()) {
            // Background syncs wait for this paint, not just for the cooldown clock: started
            // beside it they kept a boot's paint unfinished until the process was frozen
            // (StartupCooldown). Settled in `finally` so a failed or cancelled paint lets go.
            val cooldown = startupCooldown()
            cooldown.onStartupPaintPending(elapsedRealtime())
            try {
                queryResult =
                    loadStartupData(
                        database = database,
                        forecastDao = forecastDao,
                        hourlyDao = hourlyDao,
                        latestWeather = latestWeather,
                        activeSourceList = activeSources.toList(),
                        needsDailyData = needsDailyData,
                        gapFiller = gapFiller,
                        loadWindow = DailyLoadWindowResolver.resolve(context),
                    )
                // A widget's paint can be superseded by a newer one: WidgetUpdateTracker cancels a
                // tracked UI_PAINT job when another paint of that widget starts (a worker's
                // UI-only paint, Pixel 2026-11-18 07:40:03), and the batch rethrows it although
                // this coroutine was never cancelled. That aborted the live pass and left the
                // provisional paint up.
                continueIfSuperseded(
                    onSuperseded = { e ->
                        appLogDao.log(
                            "HOURLY_PAINT_TRACE",
                            "phase=startup_SUPERSEDED widgets=${unpaintedIds.joinToString(",")} msg=${e.message}",
                            "INFO",
                        )
                    },
                ) {
                    renderStartupWidgets(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetIds = unpaintedIds,
                        result = queryResult,
                        startupToken = startupToken,
                        appLogDao = appLogDao,
                    )
                }
                // Still inside the pending paint: background syncs wait for the live pass
                // too, so the blend does not contend with them (StartupCooldown).
                if (needsDailyData) {
                    livePass = refreshLiveActuals(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetIds = unpaintedIds,
                        result = queryResult,
                        latestWeather = latestWeather,
                        activeSourceList = activeSources.toList(),
                        stateManager = stateManager,
                        startupToken = startupToken,
                        appLogDao = appLogDao,
                    )
                }
            } finally {
                cooldown.onStartupPaintSettled()
            }
        }
        return StartupPaint(queryResult, livePass, checkStalenessAndFetch(context))
    }

    private suspend fun loadStartupData(
        database: WeatherDatabase,
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
                    // Past days' "yesterday's forecast" candidates, after the newest rows so each
                    // day's first row is still its newest.
                    val priorCandidates =
                        forecastDao.getPriorForecastCandidates(
                            pastSnapshotStart,
                            pastSnapshotEnd,
                            latestWeather.locationLat,
                            latestWeather.locationLon,
                        ).filter { it.source in activeSourceList }
                    (past + recent + priorCandidates).distinct()
                }
            }
        val hourlyDeferred =
            operationTimer.async(this) {
                // Same source restriction the daily query above already uses: activeSourceList is
                // every widget's display source plus GENERIC_GAP. Unfiltered this returned every
                // source ever fetched, for consumers that filter to the display source anyway.
                HourlyForecastLoader.withHistory(
                    database = database,
                    startMs = hourlyStart,
                    endMs = hourlyEnd,
                    lat = latestWeather.locationLat,
                    lon = latestWeather.locationLon,
                    sources = activeSourceList,
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
        // Stored rows only: the live blend (13.2 s of a 20 s cold first paint on the Pixel 7 Pro,
        // 2026-11-18) runs after the first paint, in refreshLiveActuals.
        // performance/261118-cold-start-daily-actuals-cost.md
        val dailyActualsDeferred =
            operationTimer.async(this) {
                if (!needsDailyData) {
                    emptyMap()
                } else {
                    repository.getDailyActualsStored(
                        latitude = latestWeather.locationLat,
                        longitude = latestWeather.locationLon,
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

    /**
     * The live daily actuals after the provisional first paint; repaints only the widgets whose
     * display source's actuals changed ([StartupActualsRefresh]).
     */
    private suspend fun refreshLiveActuals(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        result: StartupQueryResult,
        latestWeather: ForecastEntity,
        activeSourceList: List<String>,
        stateManager: WidgetStateManager,
        startupToken: String,
        appLogDao: AppLogDao,
    ): LiveActualsPass {
        val startMs = elapsedRealtime()
        // The provisional paint is already on screen: a failed live read leaves it there for the
        // next sync's paint, and must not skip the staleness check and periodic schedule after it.
        val live = try {
            repository.getDailyActualsWithLiveToday(
                latitude = latestWeather.locationLat,
                longitude = latestWeather.locationLon,
                hourlyForecasts = result.hourlyForecasts,
                activeSourceList = activeSourceList,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "startup live actuals failed token=$startupToken; keeping the provisional paint", e)
            appLogDao.log("STARTUP_LIVE_ACTUALS", "token=$startupToken outcome=failed msg=${e.message}", "WARN")
            return LiveActualsPass(elapsedRealtime() - startMs, IntArray(0), emptySet())
        }
        val durationMs = elapsedRealtime() - startMs
        val changed = StartupActualsRefresh.changedSources(result.dailyActualsBySource, live)
        val repaint = StartupActualsRefresh.widgetsToRepaint(
            appWidgetIds.associateWith { stateManager.getCurrentDisplaySource(it).id },
            changed,
            result.dailyActualsBySource,
            LocalDate.now(),
        )
        appLogDao.log(
            "STARTUP_LIVE_ACTUALS",
            "token=$startupToken liveMs=$durationMs changed=${changed.sorted()} repaint=${repaint.joinToString(",")} " +
                "diff=[${changed.sorted().joinToString("; ") { "$it ${StartupActualsRefresh.describeChange(result.dailyActualsBySource, live, it)}" }}]",
            "INFO",
        )
        if (repaint.isNotEmpty()) {
            renderStartupWidgets(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = repaint,
                result = result.copy(dailyActualsBySource = live),
                startupToken = startupToken,
                appLogDao = appLogDao,
                actualsProvisional = false,
            )
        }
        return LiveActualsPass(durationMs, repaint, changed)
    }

    private suspend fun renderStartupWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        result: StartupQueryResult,
        startupToken: String,
        appLogDao: AppLogDao,
        /** True for the first pass (stored-rows actuals; see [refreshLiveActuals]). */
        actualsProvisional: Boolean = true,
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
                        // Only meaningful when the batch loaded daily data: otherwise the map is
                        // empty either way and the daily handler is not reached.
                        actualsProvisional = actualsProvisional,
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

    private suspend fun claimStartupPaints(
        appWidgetIds: IntArray,
        claimVia: String,
        appLogDao: AppLogDao,
    ): IntArray {
        val now = elapsedRealtime()
        return appWidgetIds.filter { appWidgetId ->
            when (val decision = StartupPaintClaims.claim(appWidgetId, claimVia, now)) {
                StartupPaintClaims.Decision.Granted -> true
                is StartupPaintClaims.Decision.Repeat -> {
                    appLogDao.log(
                        "STARTUP_PAINT_REPEAT",
                        "widget=$appWidgetId via=$claimVia holderAgeMs=${decision.holderAgeMs}",
                        "INFO",
                    )
                    true
                }
                is StartupPaintClaims.Decision.Skip -> {
                    appLogDao.log(
                        "STARTUP_PAINT_SKIP",
                        "widget=$appWidgetId via=$claimVia holder=${decision.holder}",
                        "INFO",
                    )
                    false
                }
            }
        }.toIntArray()
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

    internal companion object {
        const val TAG = "WidgetStartupCoordinator"
        const val STARTUP_CLAIM_VIA = "on_update"
    }
}
