package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import androidx.work.ListenableWorker
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.shared.util.LocationChangePaintPolicy
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The full-sync pipeline: GPS resample → location resolution/promotion → fetch → backfill →
 * actuals recompute → repaint → schedule. Extracted from [WeatherWidgetWorker] so the worker is a
 * thin dispatcher and this pipeline owns its timing (`logStage`, `SYNC_PERF`) and its side-effects
 * (debug fast-refresh, no-hourly broadcast, post-run loop management).
 */
internal class FullSyncPipeline(
    private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    private val gpsResampler: GpsResampler,
    private val hourlyForecastLoader: HourlyForecastLoader,
    private val dataBundleLoader: WidgetDataBundleLoader,
    private val painter: WidgetPaintCoordinator,
    private val metarRefresher: MetarObservationRefresher,
    private val synopticRefresher: SynopticObservationRefresher,
) {
    suspend fun run(input: WorkInput, device: DeviceContext, stopReason: Int): ListenableWorker.Result {
        try {
            val startMs = SystemClock.elapsedRealtime()

            // A source retry armed with the screen on may come due in a pocket on battery: skip it there.
            if (input.currentTempReason == com.weatherwidget.shared.util.SourceRetry.REASON &&
                !com.weatherwidget.shared.util.SourceRetry.shouldFire(
                    device.isCharging, device.isScreenInteractive, input.targetSourceId ?: "?",
                ) { tag, message, level -> appLogDao.log(tag, message, level) }
            ) {
                return ListenableWorker.Result.success()
            }

            maybeScheduleDebugFastRefresh()

            // GPS resample piggybacks on full syncs
            if (!input.uiOnlyRefresh && !input.currentTempOnly && !input.nonPrimaryCurrentTempOnly &&
                !input.observationBackfillMode && !input.candidateLocationRefresh
            ) {
                try {
                    gpsResampler.maybeResample(context, trigger = "worker")
                } catch (e: Exception) {
                    Log.w(TAG, "GPS resample failed", e)
                }
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val activeLocation = ActiveLocationResolver.resolve(
                context,
                widgetStateManager,
                WeatherDatabase.getDatabase(context).forecastDao(),
            )
            // One location source. `GpsResampler.resample` above has already applied any move, so
            // `activeLocation` is current as of this run -- there is no pending candidate to prefer
            // over it (plans/260828-remove-the-location-handoff-policy.md).
            val location = activeLocation
                ?: return painter.renderNoLocationAndFinish("full_sync")
            // A setup-screen location change: say which place we are fetching for before the fetch,
            // unless the site already has today's row (then the cache repaint draws it correctly).
            var adoptedCache = false
            input.locationChangePlace?.let { place ->
                if (input.locationChangeBanner) {
                    appLogDao.log(LocationChangePaintPolicy.TAG, LocationChangePaintPolicy.line(LocationChangePaintPolicy.Action.BANNER_SYNC_START, place), "INFO")
                    adoptedCache = if (input.locationCacheAdopted) {
                        // The startup deferral already drew the cache and dropped the banner.
                        appLogDao.log(LocationChangePaintPolicy.TAG, LocationChangePaintPolicy.line(LocationChangePaintPolicy.Action.CACHE_ALREADY_ADOPTED, place), "INFO")
                        true
                    } else {
                        painter.adoptCachedNewSite(place, location.first, location.second)
                    }
                } else {
                    painter.paintLocationChangeInterstitial(place, location.first, location.second)
                }
            }
            // A location change never forces: each source fetches when its own cadence says it is
            // due at the new site (`isStale` reads per-site markers), so a site with nothing to draw
            // (no hourly fetched there within a day, longer than any cadence) is fetched on this run,
            // and a fresh one is not. A forced untargeted sync refetched every visible source,
            // Google included: 60 s under the banner on 2026-09-29, and NWS refetched at 86 min old
            // on 2026-11-21 (user: "let the stale fetching code take care of it").
            // plans/261121-location-change-honours-fresh-cache.md
            val forceFetch = input.forceRefresh && input.locationChangePlace == null
            // Null once the cache is adopted: its banner is already cleared, and a failed refresh
            // must not paint an error over the new site's good cached graph.
            val bannerPlace = input.locationChangePlace?.takeIf { input.locationChangeBanner && !adoptedCache }
            Log.d(
                TAG,
                "doWork: Location = $location " +
                    "(configured=${appWidgetIds.toList().firstNotNullOfOrNull { widgetStateManager.getWidgetLocation(it) } != null})",
            )

            val activeSourceList = hourlyForecastLoader.currentDisplaySourceIds()
            // Always the cadence context, forced or not: a forced sync forces only its target (or
            // everything when untargeted), and every other source must be judged by the same cadence
            // a scheduled sync uses. Without a context the coordinator fell back to rank thresholds
            // (60/90/120 min) and a Google-targeted force refetched NWS after 90 minutes.
            val fetchContext = ForecastFetchContext(
                isCharging = device.isCharging,
                isScreenInteractive = device.isScreenInteractive,
                batteryLevel = device.batteryLevel,
                activeSourceIds = activeSourceList.toSet(),
                userRefresh = input.userRefresh,
                allSources = input.allSources,
                skipSourceIds = input.skipSourceIds,
                wakeAtMs = device.wakeAtMs,
            )

            val networkAllowed = WidgetRefreshPolicy.isNetworkAllowedForWorker(input.uiOnlyRefresh)
            // Climate normals settle in parallel with the fetch and paint, never before them: the
            // paint draws what is cached now, and newly cached normals enqueue their own paint-only
            // repaint (`:shared` ClimateNormalsRun.ensureForSite). The scope ends after both, so the
            // worker does not finish under a running settle.
            // plans/261122-climate-normals-warm-and-repaint-shared.md
            return kotlinx.coroutines.coroutineScope {
                if (networkAllowed) {
                    launch { weatherRepository.settleClimateNormals(location.first, location.second, reason = "sync") }
                }
                val result = weatherRepository.getWeatherData(
                    latitude = location.first,
                    longitude = location.second,
                    // A location change no longer forces a fetch past the battery budget. It does not
                    // need to: `isStale` derives its last-fetch time from the *location-scoped* forecast
                    // rows, so a site with no rows is due immediately and gets fetched on this very run.
                    // What the bypass actually did was let a jittering fix force a fetch every sync, and
                    // it was tolerable only while the handoff policy made location changes rare. With
                    // promotion immediate (plans/260828-remove-the-location-handoff-policy.md), one
                    // budget governs fetch cost and nothing governs what the user is shown.
                    forceRefresh = forceFetch && !input.uiOnlyRefresh,
                    networkAllowed = networkAllowed,
                    targetSourceId = input.targetSourceId,
                    fetchContext = fetchContext,
                    requestedAtMs = input.requestedAtMs,
                )

                result.fold(
                    onSuccess = { weatherList ->
                        onFetchSuccess(weatherList, input, device, location, activeSourceList, bannerPlace, startMs)
                    },
                    onFailure = { e ->
                        appLogDao.log("SYNC_FAILURE", "Repository failed: ${e.message}", "ERROR")
                        SyncDoneNotice.announce(context, widgetStateManager, input, syncOk = false)
                        painter.renderPendingLocationFetchFailure("sync_failure")
                        bannerPlace?.let { painter.finishLocationChangeBanner(it, succeeded = false, reason = "sync_failure") }
                        ListenableWorker.Result.retry()
                    },
                )
            }
        } catch (e: CancellationException) {
            appLogDao.log("SYNC_CANCELLED", "Worker cancelled. stopReason=$stopReason msg=${e.message}", "INFO")
            throw e
        } catch (e: Exception) {
            appLogDao.logException("SYNC_EXCEPTION", "Synchronization failed", e)
            SyncDoneNotice.announce(context, widgetStateManager, input, syncOk = false)
            runCatching { painter.renderPendingLocationFetchFailure("sync_exception") }
            input.locationChangePlace?.takeIf { input.locationChangeBanner }?.let { place ->
                runCatching { painter.finishLocationChangeBanner(place, succeeded = false, reason = "sync_exception") }
            }
            return ListenableWorker.Result.retry()
        }
    }

    /**
     * The fetch-succeeded half of [run]: backfill, actuals, repaint, schedule. Split out (2026-11-19)
     * because `Result.fold` inlines its lambdas, which made [run] the largest method in the APK
     * (30,646 dex instructions, 456 registers; plans/261119-split-oversized-android-suspend-functions.md).
     */
    private suspend fun onFetchSuccess(
        weatherList: List<com.weatherwidget.data.local.ForecastEntity>,
        input: WorkInput,
        device: DeviceContext,
        location: Pair<Double, Double>,
        activeSourceList: List<String>,
        bannerPlace: String?,
        startMs: Long,
    ): ListenableWorker.Result {
        val load = loadAndBackfill(weatherList, input, location)
        val actuals = recomputeActuals(weatherList, input, location, activeSourceList, load)
        val afterUpdateMs = paintAll(weatherList, input, location, bannerPlace, load, actuals)
        logSyncPerf(input, startMs, load, actuals, afterUpdateMs)
        scheduleAfterSync(input, device)

        SyncDoneNotice.announce(context, widgetStateManager, input, syncOk = true)
        return ListenableWorker.Result.success()
    }

    /** The paint of [onFetchSuccess] and its banner bookkeeping; returns when it finished. */
    private suspend fun paintAll(
        weatherList: List<com.weatherwidget.data.local.ForecastEntity>,
        input: WorkInput,
        location: Pair<Double, Double>,
        bannerPlace: String?,
        load: LoadPhase,
        actuals: ActualsPhase,
    ): Long {
        appLogDao.log(
            "WIDGET_LIFECYCLE",
            "phase=worker_paint_start uiOnly=${input.uiOnlyRefresh} thread=${Thread.currentThread().name}",
        )
        val jobType = if (input.uiOnlyRefresh) WidgetUpdateTracker.JobType.UI_PAINT else WidgetUpdateTracker.JobType.BACKGROUND_SYNC
        val workerOrigin = if (input.uiOnlyRefresh) WidgetPushDispatcher.Origin.UI_ONLY else WidgetPushDispatcher.Origin.WORKER_FETCH
        // Before the paint, so the new site's render binds the banner GONE itself.
        if (bannerPlace != null && weatherList.isNotEmpty()) {
            painter.finishLocationChangeBanner(bannerPlace, succeeded = true, reason = "sync_success")
        }
        painter.updateAllWidgets(
            weatherList = weatherList,
            forecastSnapshots = load.forecastSnapshots,
            hourlyForecasts = actuals.renderHourlyForecasts,
            currentTemps = actuals.currentTemps,
            dailyActuals = actuals.dailyActuals,
            jobType = jobType,
            uiOnly = input.uiOnlyRefresh,
            origin = workerOrigin,
            loadedActualsSourceIds = actuals.actualsSourceList,
            loadedHourlySourceIds = actuals.renderHourlySourceIds,
            hourlyLat = location.first,
            hourlyLon = location.second,
            // This escape hatch fires when a widget's source changed during the paint —
            // the same race, one stage later — so it must read the reloaded rows too.
            reloadActuals = { sourceIds, hourly ->
                dataBundleLoader.reloadDailyActuals(
                    lat = location.first,
                    lon = location.second,
                    hourlyForecasts = hourly,
                    sourceIds = sourceIds,
                )
            },
        )
        val afterUpdateMs = SystemClock.elapsedRealtime()
        appLogDao.log(
            "WIDGET_LIFECYCLE",
            "phase=worker_paint_done uiOnly=${input.uiOnlyRefresh} elapsedMs=${afterUpdateMs - actuals.afterActualsMs}",
        )
        // "Success" with no rows is a failure from the interstitial's point of view: every
        // source declined and the repository fell back to an (empty) cache. Nothing above
        // cleared the wait, so give the user the tap-to-refresh way out.
        if (weatherList.isEmpty()) {
            painter.renderPendingLocationFetchFailure("sync_success_empty")
            bannerPlace?.let { painter.finishLocationChangeBanner(it, succeeded = false, reason = "sync_success_empty") }
        }

        return afterUpdateMs
    }

    /** SYNC_PERF for a slow [onFetchSuccess]. */
    private suspend fun logSyncPerf(
        input: WorkInput,
        startMs: Long,
        load: LoadPhase,
        actuals: ActualsPhase,
        afterUpdateMs: Long,
    ) {
        val totalMs = afterUpdateMs - startMs
        if (totalMs > 500) {
            appLogDao.log(
                "SYNC_PERF",
                "uiOnly=${input.uiOnlyRefresh} total=${totalMs}ms " +
                    "weather=${load.afterWeatherMs - startMs}ms " +
                    "hourly=${load.afterHourlyMs - load.afterWeatherMs}ms " +
                    "backfill=${load.afterBackfillMs - load.afterHourlyMs}ms " +
                    "[nwsObs=${load.afterNwsBackfillMs - load.afterHourlyMs}ms " +
                    "metar=${load.metarDoneMs - load.afterNwsBackfillMs}ms " +
                    "synoptic=${load.afterBackfillMs - load.metarDoneMs}ms] " +
                    "actuals=${actuals.afterActualsMs - load.afterBackfillMs}ms " +
                    "[currentTemps=${actuals.afterCurrentTempsMs - load.afterBackfillMs}ms " +
                    "recompute=${actuals.afterRecomputeMs - actuals.afterCurrentTempsMs}ms " +
                    "repairs=${actuals.afterRepairsMs - actuals.afterRecomputeMs}ms " +
                    "notifier=${actuals.afterActualsMs - actuals.afterRepairsMs}ms] " +
                    "widgets=${afterUpdateMs - actuals.afterActualsMs}ms",
            )
        }
    }

    /** What a finished sync arms next (UI loop for a UI-only run). */
    private suspend fun scheduleAfterSync(input: WorkInput, device: DeviceContext) {
        if (!input.uiOnlyRefresh) {
            UIUpdateScheduler(context).scheduleNextUpdate()
            // Re-pin the periodic full-sync cadence to the battery state observed at the
            // end of this run. ACTION_BATTERY_CHANGED cannot be manifest-registered
            // (sticky broadcast), so without this the periodic interval stays pinned to
            // the value captured at startup / the last power transition and never
            // self-corrects as the battery level drifts (see code-review M3).
            WidgetWorkScheduler.schedulePeriodicSync(context)
            PreUnplugFetchScheduler.runner(context).let {
                it.arm(trigger = "sync")
                it.dailyPatternLog()
            }
            // The next due-time sync, from the plan this sync just moved
            // (shared ForecastSchedule; the periodic work is only a backstop).
            DueWakePlanner.plan(context, weatherRepository, widgetStateManager, appLogDao, onlyIfMissing = false)
            UsageHoursTracker.dailyLogLines(context).forEach { (tag, line) -> appLogDao.log(tag, line, "INFO") }
        } else {
            WidgetLoopScheduler.manageCurrentTempLoopAfterRun(context, appLogDao, device)
        }
    }

    /** What [loadAndBackfill] hands to the rest of [onFetchSuccess]; the timestamps feed SYNC_PERF. */
    private class LoadPhase(
        val afterWeatherMs: Long,
        val forecastSnapshots: Map<LocalDate, List<com.weatherwidget.data.local.ForecastEntity>>,
        val hourlySourceIdsAtLoad: List<String>,
        val hourlyForecasts: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        val afterHourlyMs: Long,
        val afterNwsBackfillMs: Long,
        val metarDoneMs: Long,
        val afterBackfillMs: Long,
    )

    /** Snapshots, hourly rows and the observation backfills of [onFetchSuccess]. */
    private suspend fun loadAndBackfill(
        weatherList: List<com.weatherwidget.data.local.ForecastEntity>,
        input: WorkInput,
        location: Pair<Double, Double>,
    ): LoadPhase {
        val afterWeatherMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "doWork: Got ${weatherList.size} weather entries")
        logStage("weather_fetched count=${weatherList.size}")

        val forecastSnapshots = dataBundleLoader.fetchForecastSnapshots(location.first, location.second)
        // Snapshotted here, re-read at paint time below: the gap between the two spans
        // the fetch/backfill/actuals stages (seconds), and a source toggle inside it
        // makes the loaded rows unusable for the toggled widget.
        val hourlySourceIdsAtLoad = hourlyForecastLoader.hourlySourceIds()
        val hourlyForecasts = hourlyForecastLoader.load(
            lat = location.first,
            lon = location.second,
            sources = hourlySourceIdsAtLoad,
            caller = "full_sync",
        )
        val afterHourlyMs = SystemClock.elapsedRealtime()
        logStage("hourly_fetched count=${hourlyForecasts.size}")

        // NWS station history is wanted by NWS itself AND by any source whose actuals
        // come from NWS (Google Weather with the NWS provider got none before). The gate
        // is the shared NwsGapFillRun.dailyForSources, the same one desktop runs.
        val sourcesServed = input.targetSourceId?.let { listOf(WeatherSource.fromId(it)) }
            ?: (widgetStateManager.getVisibleSourcesOrder() +
                weatherList.map { WeatherSource.fromId(it.source) })
        if (!input.uiOnlyRefresh) {
            weatherRepository.backfillNwsObservationsIfNeeded(
                location.first, location.second, sourcesServed, reason = "full_sync",
            )
        }
        val afterNwsBackfillMs = SystemClock.elapsedRealtime()
        // Gated on `!uiOnlyRefresh` for the same reason the NWS backfill above is: a
        // UI-only refresh exists precisely to repaint from cache WITHOUT touching the
        // network. Measured 2026-08-23 — ungated, a `uiOnly=true` run issued a live
        // METAR request, which is exactly the battery cost that flag exists to avoid.
        //
        // Placed before the actuals recompute below, not after: rows landing later
        // would sit unused until the next cycle. Accepts BOTH tiers because the full
        // sync is the battery-aware main fetch AND the path every user-initiated
        // refresh and source toggle takes (both log `SYNC_START force=true`), making it
        // the only place METAR reliably gets a turn.
        var metarDoneMs = afterNwsBackfillMs
        if (!input.uiOnlyRefresh) {
            metarRefresher.refreshIfDue(
                setOf(com.weatherwidget.shared.util.MetarFetchPolicy.Tier.PRIMARY, com.weatherwidget.shared.util.MetarFetchPolicy.Tier.NON_PRIMARY),
                location.first,
                location.second,
                reason = "full_sync",
                // Deep pull: daily extremes need the whole day, not the last two hours.
                hours = MetarObservationRefresher.DEEP_HOURS,
            )
            metarDoneMs = SystemClock.elapsedRealtime()
            synopticRefresher.refreshIfDue(
                setOf(com.weatherwidget.shared.util.SynopticFetchPolicy.Tier.PRIMARY, com.weatherwidget.shared.util.SynopticFetchPolicy.Tier.NON_PRIMARY),
                location.first,
                location.second,
                reason = "full_sync",
                // A backoff earned at the previous site must not blank the new one's
                // actuals for 30 min after the user moved it.
                userLocationChange = input.locationChangePlace != null,
            )
        }

        val afterBackfillMs = SystemClock.elapsedRealtime()
        logStage("backfill_done")

        return LoadPhase(afterWeatherMs, forecastSnapshots, hourlySourceIdsAtLoad, hourlyForecasts, afterHourlyMs, afterNwsBackfillMs, metarDoneMs, afterBackfillMs)
    }

    /** What [recomputeActuals] hands to the paint; the timestamps feed SYNC_PERF. */
    private class ActualsPhase(
        val currentTemps: List<com.weatherwidget.data.local.ObservationEntity>,
        val afterCurrentTempsMs: Long,
        val actualsSourceList: List<String>,
        val renderHourlyForecasts: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        val renderHourlySourceIds: List<String>,
        val dailyActuals: DailyActualsBySource,
        val afterRecomputeMs: Long,
        val afterRepairsMs: Long,
        val afterActualsMs: Long,
    )

    /** Current temps, the paint-time hourly re-read and the daily actuals recompute of [onFetchSuccess]. */
    private suspend fun recomputeActuals(
        weatherList: List<com.weatherwidget.data.local.ForecastEntity>,
        input: WorkInput,
        location: Pair<Double, Double>,
        activeSourceList: List<String>,
        load: LoadPhase,
    ): ActualsPhase {
        val forecastSnapshots = load.forecastSnapshots
        val hourlyForecasts = load.hourlyForecasts
        val hourlySourceIdsAtLoad = load.hourlySourceIdsAtLoad
        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
            location.first, location.second, todayStartMs,
        )
        val afterCurrentTempsMs = SystemClock.elapsedRealtime()

        appLogDao.log(
            "SYNC_SUCCESS",
            "Weather=${weatherList.size}, Snapshots=${forecastSnapshots.size}, Hourly=${hourlyForecasts.size}",
            "INFO",
        )

        logStage("actuals_recompute_start recompute=${!input.uiOnlyRefresh}")
        val actualsSourceList = hourlyForecastLoader.currentDisplaySourceIds()

        // `hourlyForecasts` was scoped to `hourlySourceIdsAtLoad`, read BEFORE the fetch.
        // A source toggle during the fetch (seconds) leaves that list with ZERO rows for
        // the newly displayed source, so both the actuals recompute and the repaint below
        // would work from data that cannot contain it: the widget paints an empty graph
        // ("no cloud data") and the gap detector then burns a redundant forced sync.
        // Re-read once — a single scoped query — so everything downstream sees the
        // sources actually on screen. Cross-check HOURLY_SOURCE_MISS in WidgetRenderer.
        val missingAtPaint = HourlyForecastLoader.sourcesMissingFromLoad(
            loadedSourceIds = hourlySourceIdsAtLoad,
            displaySourceIdsAtPaint = actualsSourceList,
        )
        val renderHourlyForecasts = if (missingAtPaint.isEmpty()) {
            hourlyForecasts
        } else {
            val reloaded = hourlyForecastLoader.load(
                lat = location.first,
                lon = location.second,
                sources = hourlyForecastLoader.hourlySourceIds(),
                caller = "full_sync_source_reload",
            )
            appLogDao.log(
                "HOURLY_SOURCE_SNAPSHOT_STALE",
                "loaded=${hourlySourceIdsAtLoad.joinToString("|")} " +
                    "atPaint=${actualsSourceList.joinToString("|")} " +
                    "missing=${missingAtPaint.joinToString("|")} " +
                    "staleRows=${hourlyForecasts.size} reloadedRows=${reloaded.size}",
                "WARN",
            )
            reloaded
        }
        // Scope the final list was actually loaded under, so the paint-time guard in
        // WidgetPaintCoordinator can detect a toggle that lands AFTER this re-read.
        val renderHourlySourceIds =
            if (missingAtPaint.isEmpty()) hourlySourceIdsAtLoad
            else hourlyForecastLoader.hourlySourceIds()

        val dailyActuals = dataBundleLoader.fetchDailyActuals(
            lat = location.first,
            lon = location.second,
            hourlyForecasts = renderHourlyForecasts,
            activeSourceList = actualsSourceList,
            recompute = !input.uiOnlyRefresh,
        )
        val afterRecomputeMs = SystemClock.elapsedRealtime()

        if (!input.uiOnlyRefresh) {
            weatherRepository.dailyHistoryAfterFetch(location.first, location.second)
            weatherRepository.repairFrozenRainChanceIfNeeded(location.first, location.second)
            weatherRepository.dailyHistoryUpkeepIfDue(
                location.first,
                location.second,
                isCharging = BatterySnapshotProvider.snapshot(context).isCharging,
            )
        }
        val afterRepairsMs = SystemClock.elapsedRealtime()

        // One-shot dominant-station temperature watch. Costs a single boolean read when
        // the user has not armed it, and must never fail a sync — it is an optional
        // notification, not part of producing the widget.
        try {
            com.weatherwidget.notify.DominantTempChangeNotifier.check(
                context = context,
                repository = weatherRepository,
                stateManager = widgetStateManager,
                appLogDao = appLogDao,
                lat = location.first,
                lon = location.second,
                origin = "full_sync",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.logException("DOMINANT_TEMP_WATCH", "check failed (full_sync)", e)
        }
        val afterActualsMs = SystemClock.elapsedRealtime()
        return ActualsPhase(currentTemps, afterCurrentTempsMs, actualsSourceList, renderHourlyForecasts, renderHourlySourceIds, dailyActuals, afterRecomputeMs, afterRepairsMs, afterActualsMs)
    }

    private suspend fun logStage(stage: String) {
        if (!com.weatherwidget.BuildConfig.DEBUG) return
        appLogDao.log("SYNC_STAGE", "stage=$stage thread=${Thread.currentThread().name}", "INFO")
    }

    private fun maybeScheduleDebugFastRefresh() {
        if (!com.weatherwidget.BuildConfig.DEBUG || DEBUG_FAST_FULL_REFRESH_SECONDS <= 0L) return
        val request = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInitialDelay(DEBUG_FAST_FULL_REFRESH_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                    .tagTestModeEnqueue()
                    .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "debug_fast_refresh")
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_DEBUG_FAST_REFRESH,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Scheduled debug fast refresh in ${DEBUG_FAST_FULL_REFRESH_SECONDS}s")
    }

    companion object {
        private const val TAG = "FullSyncPipeline"
        private const val WORK_NAME_DEBUG_FAST_REFRESH = "weather_widget_debug_fast_refresh"
        private const val DEBUG_FAST_FULL_REFRESH_SECONDS = 0L
    }
}
