package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@HiltWorker
class WeatherWidgetWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted workerParams: WorkerParameters,
        private val weatherRepository: WeatherRepository,
        private val widgetStateManager: WidgetStateManager,
        private val appLogDao: AppLogDao,
        private val gpsResampler: GpsResampler,
    ) : CoroutineWorker(context, workerParams) {

        private val hourlyForecastLoader by lazy { HourlyForecastLoader(context, widgetStateManager) }
        private val dataBundleLoader by lazy { WidgetDataBundleLoader(weatherRepository, hourlyForecastLoader, context) }
        private val lastRenderMs = mutableMapOf<Int, Long>()

        // ---- orchestration ----

        override suspend fun doWork(): Result {
            if (WeatherDatabase.isTestingMode()) {
                Log.d(TAG, "Skipping worker execution in test mode")
                return Result.success()
            }

            ProcessExitLogger.logRecentExitsOnce(context, appLogDao)

            val input = WorkInput.from(inputData)
            val device = measureDeviceContext()
            appLogDao.log(
                "SYNC_START",
                "uiOnly=${input.uiOnlyRefresh}, force=${input.forceRefresh}, currentOnly=${input.currentTempOnly}, " +
                    "opportunistic=${input.opportunisticCurrentTemp}, battery=${device.batteryLevel}%, " +
                    "plugged=${device.isCharging}, interactive=${device.isScreenInteractive}, " +
                    "reason=${input.currentTempReason}, obsBackfillOnly=${input.observationBackfillMode}, " +
                    "lastFullFetch=${device.lastFullFetchAgeSeconds}s ago",
            )

            if (!input.forceRefresh && !input.uiOnlyRefresh && !input.currentTempOnly &&
                !input.observationBackfillMode && device.lastFullFetchAgeSeconds in 0..300
            ) {
                appLogDao.log("SYNC_SKIP", "reason=cooldown age=${device.lastFullFetchAgeSeconds}s", "INFO")
                return Result.success()
            }

            // Emitted here rather than at Application.onCreate: the migration runs before the database
            // is safe to touch, so it leaves its report in prefs for the first worker run to persist.
            // This block owns the database half of that migration too — purging the forecast rows filed
            // at the retired sentinel, which prefs-clearing alone left free to resurrect it. It sits
            // above every ActiveLocationResolver.resolve() call in this file on purpose.
            completeLegacyDefaultMigration()

            if (input.observationBackfillMode) {
                return handleObservationBackfillWork(input)
            }
            if (input.currentTempOnly) {
                return handleCurrentTempOnlyWork(input, device)
            }
            if (input.nonPrimaryCurrentTempOnly) {
                return handleNonPrimaryCurrentTempOnlyWork(input, device)
            }

            return handleFullSyncWork(input, device)
        }

        /**
         * Database half of [LegacyDefaultLocationMigration]: deletes the forecast rows filed at the
         * retired Google-HQ sentinel, then persists the migration's deferred report.
         *
         * Purge **before** consuming the report. The pending report is what suppresses the
         * cached-weather fallback in [ActiveLocationResolver.resolve]; consuming it after a failed
         * purge would re-open that fallback with the rows still sitting there, which is precisely the
         * resurrection this fixes. A failure therefore leaves the flag set and retries next run —
         * never fatal, since a cleanup step must not fail a sync.
         */
        private suspend fun completeLegacyDefaultMigration() {
            if (!LegacyDefaultLocationMigration.isPurgePending(context)) return
            val purged = try {
                WeatherDatabase.getDatabase(context).forecastDao().deleteForecastsAtSite(
                    LegacyDefaultLocationMigration.LEGACY_DEFAULT_LAT,
                    LegacyDefaultLocationMigration.LEGACY_DEFAULT_LON,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogDao.logException(
                    "LOCATION_MIGRATION",
                    "Sentinel forecast purge failed; leaving the flag set to retry next run",
                    e,
                )
                return
            }
            LegacyDefaultLocationMigration.consumePendingReport(context)?.let { report ->
                appLogDao.log("LOCATION_MIGRATION", "$report rows_purged=$purged", "INFO")
            }
        }

        // ---- work-mode handlers ----

        private suspend fun handleObservationBackfillWork(input: WorkInput): Result =
            handleWorkerExceptions(
                appLogDao = appLogDao,
                cancellationTag = "OBS_BACKFILL_CANCELLED",
                cancellationMessage = "Observation backfill cancelled. reason=${input.backfillReason} stopReason=$stopReason",
                errorTag = "OBS_HOURLY_BACKFILL_EXCEPTION",
                errorMessage = "Observation backfill failed (reason=${input.backfillReason})",
                onException = { Result.failure() },
            ) {
                if (!input.backfillLat.isFinite() || !input.backfillLon.isFinite()) {
                    // Enqueued without an explicit location. Skipping loses nothing; fetching would
                    // file observations under a coordinate nobody chose, and a mis-keyed row is a
                    // permanent LocationMatch fragment that selectNearestSite silently drops later.
                    appLogDao.log(
                        "OBS_HOURLY_BACKFILL_SKIP",
                        "reason=${input.backfillReason} cause=no_location",
                        "INFO",
                    )
                    return@handleWorkerExceptions Result.success()
                }
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_RUN",
                    "reason=${input.backfillReason} lat=${input.backfillLat} lon=${input.backfillLon} lookbackHours=${input.backfillHours}",
                    "INFO",
                )
                val result = weatherRepository.backfillRecentNwsObservations(input.backfillLat, input.backfillLon, input.backfillHours)
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_RESULT",
                    "reason=${input.backfillReason} stations=${result.stationsTried} rows=${result.rowsFetched} affectedDates=${result.affectedDates.sorted()}",
                    "INFO",
                )
                refreshWidgetsFromCache()
                Result.success()
            }

        private suspend fun handleCurrentTempOnlyWork(
            input: WorkInput,
            device: DeviceContext,
        ): Result {
            val startMs = SystemClock.elapsedRealtime()
            appLogDao.log(
                "CURR_FETCH_WORK_START",
                "id=$id reason=${input.currentTempReason} isPlugged=${device.isCharging} isInteractive=${device.isScreenInteractive} opportunistic=${input.opportunisticCurrentTemp}",
            )

            val targetSource = input.targetSourceId?.let(WeatherSource::fromId)
            return try {
                val isManual = input.currentTempReason.contains("manual") || input.currentTempReason.contains("force") || input.forceRefresh
                var resultMessage = "success"
                var fetchDurationMs = 0L
                var attemptedSourceCount = 0

                if (!CurrentTempFetchPolicy.shouldFetchNow(
                        isCharging = device.isCharging,
                        isScreenInteractive = device.isScreenInteractive,
                        isOpportunisticContext = input.opportunisticCurrentTemp,
                        batteryLevel = device.batteryLevel,
                        isManual = isManual,
                    )
                ) {
                    appLogDao.log(
                        "CURR_FETCH_SKIP",
                        "reason=${input.currentTempReason} policy_blocked charging=${device.isCharging} battery=${device.batteryLevel} " +
                            "cutoff=${CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT} " +
                            "interactive=${device.isScreenInteractive} opportunistic=${input.opportunisticCurrentTemp}",
                        "INFO",
                    )
                    resultMessage = "skipped_policy_blocked"
                } else {
                    val location = ActiveLocationResolver.resolve(context, widgetStateManager, WeatherDatabase.getDatabase(context).forecastDao())
                        ?: return renderNoLocationAndFinish("current_temp_only")
                    val fetchStartMs = SystemClock.elapsedRealtime()
                    val refreshResult = weatherRepository.refreshCurrentTemperature(
                        latitude = location.first,
                        longitude = location.second,
                        locationName = getLocationName(location.first, location.second),
                        source = targetSource,
                        reason = input.currentTempReason,
                        forceRefresh = input.forceRefresh,
                    )
                    fetchDurationMs = SystemClock.elapsedRealtime() - fetchStartMs
                    refreshResult.fold(
                        onSuccess = { attempted ->
                            attemptedSourceCount = attempted
                            resultMessage = "success"
                        },
                        onFailure = { e ->
                            appLogDao.log("CURR_FETCH_FAIL", "reason=${input.currentTempReason} ${e.message}", "ERROR")
                            resultMessage = "fetch_failure:${e.message}"
                        },
                    )
                }

                val skipRepaint = CurrentTempFetchPolicy.shouldSkipPostRunRepaint(
                    policyBlocked = resultMessage == "skipped_policy_blocked",
                    fetchFailed = resultMessage.startsWith("fetch_failure"),
                    attemptedSourceCount = attemptedSourceCount,
                )
                var cacheRefreshDurationMs = 0L
                if (skipRepaint) {
                    appLogDao.log(
                        "CURR_PAINT_SKIP",
                        "reason=${input.currentTempReason} result=$resultMessage attemptedSources=$attemptedSourceCount",
                        "INFO",
                    )
                } else {
                    val cacheRefreshStartMs = SystemClock.elapsedRealtime()
                    refreshWidgetsFromCache()
                    cacheRefreshDurationMs = SystemClock.elapsedRealtime() - cacheRefreshStartMs
                }

                manageCurrentTempLoopAfterRun(device, ignoreRunningWorkId = id)

                val totalDurationMs = SystemClock.elapsedRealtime() - startMs
                if (totalDurationMs > 500) {
                    appLogDao.log(
                        "SYNC_PERF_CURRENT",
                        "reason=${input.currentTempReason} total=${totalDurationMs}ms fetch=${fetchDurationMs}ms widgets=${cacheRefreshDurationMs}ms",
                        "INFO",
                    )
                }

                appLogDao.log(
                    "CURR_FETCH_WORK_RESULT",
                    "id=$id reason=${input.currentTempReason} result=$resultMessage",
                    "INFO",
                )
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                appLogDao.log(
                    "CURR_FETCH_CANCELLED",
                    "CurrentTemp fetch cancelled. reason=${input.currentTempReason} stopReason=$stopReason durationMs=$durationMs msg=${e.message}",
                    "INFO",
                )
                throw e
            } catch (e: Exception) {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                appLogDao.logException("CURR_FETCH_EXCEPTION", "CurrentTemp fetch failed (reason=${input.currentTempReason}, duration=${durationMs}ms)", e)
                manageCurrentTempLoopAfterRun(device, ignoreRunningWorkId = id)
                Result.retry()
            }
        }

        private suspend fun handleNonPrimaryCurrentTempOnlyWork(
            input: WorkInput,
            device: DeviceContext,
        ): Result {
            val startMs = SystemClock.elapsedRealtime()
            appLogDao.log(
                "NONPRIMARY_FETCH_START",
                "id=$id reason=${input.currentTempReason} isPlugged=${device.isCharging} isInteractive=${device.isScreenInteractive}",
                "INFO",
            )

            return try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                val activeSourceIds = appWidgetIds.map { widgetStateManager.getCurrentDisplaySource(it).id }.distinct().toSet()
                val visibleSources = widgetStateManager.getVisibleSourcesOrder()
                val nonActiveSources = visibleSources.filter { it.id !in activeSourceIds }

                var resultMessage = "success"
                var fetchDurationMs = 0L

                if (nonActiveSources.isEmpty()) {
                    resultMessage = "no_non_active_visible_sources"
                } else {
                    val location = ActiveLocationResolver.resolve(context, widgetStateManager, WeatherDatabase.getDatabase(context).forecastDao())
                        ?: return renderNoLocationAndFinish("non_primary_current_temp")
                    val fetchStartMs = SystemClock.elapsedRealtime()
                    var successCount = 0
                    var failCount = 0
                    for (source in nonActiveSources) {
                        val refreshResult = weatherRepository.refreshCurrentTemperature(
                            latitude = location.first,
                            longitude = location.second,
                            locationName = getLocationName(location.first, location.second),
                            source = source,
                            reason = "non_primary_${input.currentTempReason}",
                            forceRefresh = input.forceRefresh,
                        )
                        refreshResult.fold(
                            onSuccess = { successCount++ },
                            onFailure = { failCount++ },
                        )
                    }
                    fetchDurationMs = SystemClock.elapsedRealtime() - fetchStartMs
                    resultMessage = "success=$successCount fail=$failCount"
                }

                val cacheRefreshStartMs = SystemClock.elapsedRealtime()
                refreshWidgetsFromCache()
                val cacheRefreshDurationMs = SystemClock.elapsedRealtime() - cacheRefreshStartMs

                manageNonPrimaryLoopAfterRun(device)

                val totalDurationMs = SystemClock.elapsedRealtime() - startMs
                appLogDao.log(
                    "NONPRIMARY_FETCH_RESULT",
                    "id=$id reason=${input.currentTempReason} result=$resultMessage total=${totalDurationMs}ms fetch=${fetchDurationMs}ms widgets=${cacheRefreshDurationMs}ms",
                    "INFO",
                )
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                appLogDao.log(
                    "NONPRIMARY_FETCH_CANCELLED",
                    "reason=${input.currentTempReason} durationMs=$durationMs msg=${e.message}",
                    "INFO",
                )
                throw e
            } catch (e: Exception) {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                appLogDao.logException("NONPRIMARY_FETCH_EXCEPTION", "NonPrimary fetch failed (reason=${input.currentTempReason}, duration=${durationMs}ms)", e)
                manageNonPrimaryLoopAfterRun(device)
                Result.retry()
            }
        }

        private suspend fun handleFullSyncWork(input: WorkInput, device: DeviceContext): Result {
            try {
                val startMs = SystemClock.elapsedRealtime()

                maybeScheduleDebugFastRefresh()

                // GPS resample piggybacks on full syncs
                var candidateChangedThisRun = false
                if (!input.uiOnlyRefresh && !input.currentTempOnly && !input.nonPrimaryCurrentTempOnly &&
                    !input.observationBackfillMode && !input.candidateLocationRefresh
                ) {
                    try {
                        candidateChangedThisRun = gpsResampler.resample(context)
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
                val candidateAtLoad = if (!input.uiOnlyRefresh) LocationHandoffStore.getCandidate(context) else null
                // Candidate first, as before. A candidate can exist with no active location yet (a GPS
                // handoff on a never-configured install), so the no-location gate is on the resolved
                // pair, not on activeLocation alone.
                val location = candidateAtLoad?.location?.let { it.lat to it.lon }
                    ?: activeLocation
                    ?: return renderNoLocationAndFinish("full_sync")
                Log.d(
                    TAG,
                    "doWork: Location = $location active=$activeLocation candidate=${candidateAtLoad != null} " +
                        "(configured=${appWidgetIds.toList().firstNotNullOfOrNull { widgetStateManager.getWidgetLocation(it) } != null})",
                )

                val activeSourceList = hourlyForecastLoader.currentDisplaySourceIds()
                val fetchContext = if (!input.forceRefresh && !input.uiOnlyRefresh) {
                    ForecastFetchContext(
                        isCharging = device.isCharging,
                        isScreenInteractive = device.isScreenInteractive,
                        batteryLevel = device.batteryLevel,
                        activeSourceIds = activeSourceList.toSet(),
                    )
                } else null

                val result = weatherRepository.getWeatherData(
                    latitude = location.first,
                    longitude = location.second,
                    forceRefresh = (input.forceRefresh || candidateChangedThisRun) && !input.uiOnlyRefresh,
                    networkAllowed = WidgetRefreshPolicy.isNetworkAllowedForWorker(input.uiOnlyRefresh),
                    targetSourceId = input.targetSourceId,
                    fetchContext = fetchContext,
                )

                return result.fold(
                    onSuccess = { weatherList ->
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
                        )
                        val afterHourlyMs = SystemClock.elapsedRealtime()
                        logStage("hourly_fetched count=${hourlyForecasts.size}")

                        if (candidateAtLoad != null) {
                            when (val outcome = tryPromoteLocationCandidate(
                                context = context,
                                appLogDao = appLogDao,
                                widgetStateManager = widgetStateManager,
                                candidateAtLoad = candidateAtLoad,
                                weatherList = weatherList,
                                hourlyForecasts = hourlyForecasts,
                                activeSourceIds = activeSourceList,
                                appWidgetIds = appWidgetIds,
                            )) {
                                is LocationCandidateOutcome.Superseded,
                                is LocationCandidateOutcome.WaitingForData,
                                is LocationCandidateOutcome.PromotionFailed,
                                -> return@fold Result.success()
                                is LocationCandidateOutcome.Promoted -> { /* continue */ }
                            }
                        }

                        if (!input.uiOnlyRefresh && (input.targetSourceId == WeatherSource.NWS.id ||
                                (input.targetSourceId == null && weatherList.any { it.source == WeatherSource.NWS.id }))
                        ) {
                            Log.d(TAG, "doWork: Triggering NWS backfill check")
                            weatherRepository.backfillNwsObservationsIfNeeded(location.first, location.second)
                        }
                        val afterBackfillMs = SystemClock.elapsedRealtime()
                        logStage("backfill_done")

                        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
                            location.first, location.second, todayStartMs,
                        )

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

                        val dailyActuals = dataBundleLoader.fetchDailyActuals(
                            lat = location.first,
                            lon = location.second,
                            hourlyForecasts = renderHourlyForecasts,
                            activeSourceList = actualsSourceList,
                            recompute = !input.uiOnlyRefresh,
                        )
                        val afterActualsMs = SystemClock.elapsedRealtime()

                        if (!input.uiOnlyRefresh) {
                            weatherRepository.snapshotDisplayedRainChance(location.first, location.second)
                            weatherRepository.backfillForecastChanceSnapshotsIfNeeded(location.first, location.second)
                            weatherRepository.backfillFrozenDisplayColumnsIfNeeded(location.first, location.second)
                            weatherRepository.repairFrozenRainChanceIfNeeded(location.first, location.second)
                        }

                        appLogDao.log(
                            "WIDGET_LIFECYCLE",
                            "phase=worker_paint_start uiOnly=${input.uiOnlyRefresh} thread=${Thread.currentThread().name}",
                        )
                        val jobType = if (input.uiOnlyRefresh) WidgetUpdateTracker.JobType.UI_PAINT else WidgetUpdateTracker.JobType.BACKGROUND_SYNC
                        val workerOrigin = if (input.uiOnlyRefresh) WidgetPushDispatcher.Origin.UI_ONLY else WidgetPushDispatcher.Origin.WORKER_FETCH
                        updateAllWidgets(
                            weatherList = weatherList,
                            forecastSnapshots = forecastSnapshots,
                            hourlyForecasts = renderHourlyForecasts,
                            currentTemps = currentTemps,
                            dailyActuals = dailyActuals,
                            jobType = jobType,
                            uiOnly = input.uiOnlyRefresh,
                            origin = workerOrigin,
                            loadedActualsSourceIds = actualsSourceList,
                            // This escape hatch fires when a widget's source changed during the paint —
                            // the same race, one stage later — so it must read the reloaded rows too.
                            reloadActuals = { sourceIds ->
                                dataBundleLoader.reloadDailyActuals(
                                    lat = location.first,
                                    lon = location.second,
                                    hourlyForecasts = renderHourlyForecasts,
                                    sourceIds = sourceIds,
                                )
                            },
                        )
                        val afterUpdateMs = SystemClock.elapsedRealtime()
                        appLogDao.log(
                            "WIDGET_LIFECYCLE",
                            "phase=worker_paint_done uiOnly=${input.uiOnlyRefresh} elapsedMs=${afterUpdateMs - afterActualsMs}",
                        )

                        val totalMs = afterUpdateMs - startMs
                        if (totalMs > 500) {
                            appLogDao.log(
                                "SYNC_PERF",
                                "uiOnly=${input.uiOnlyRefresh} total=${totalMs}ms " +
                                    "weather=${afterWeatherMs - startMs}ms " +
                                    "hourly=${afterHourlyMs - afterWeatherMs}ms " +
                                    "backfill=${afterBackfillMs - afterHourlyMs}ms " +
                                    "actuals=${afterActualsMs - afterBackfillMs}ms " +
                                    "widgets=${afterUpdateMs - afterActualsMs}ms",
                            )
                        }

                        if (!input.uiOnlyRefresh) {
                            UIUpdateScheduler(context).scheduleNextUpdate()
                            // Re-pin the periodic full-sync cadence to the battery state observed at the
                            // end of this run. ACTION_BATTERY_CHANGED cannot be manifest-registered
                            // (sticky broadcast), so without this the periodic interval stays pinned to
                            // the value captured at startup / the last power transition and never
                            // self-corrects as the battery level drifts (see code-review M3).
                            WidgetWorkScheduler.schedulePeriodicSync(context)
                        } else {
                            manageCurrentTempLoopAfterRun(device)
                        }

                        Result.success()
                    },
                    onFailure = { e ->
                        appLogDao.log("SYNC_FAILURE", "Repository failed: ${e.message}", "ERROR")
                        Result.retry()
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                appLogDao.log("SYNC_CANCELLED", "Worker cancelled. stopReason=$stopReason msg=${e.message}", "INFO")
                throw e
            } catch (e: Exception) {
                appLogDao.logException("SYNC_EXCEPTION", "Synchronization failed", e)
                return Result.retry()
            } finally {
                if (input.shouldBroadcastNoHourlyComplete) {
                    broadcastNoHourlyRefreshComplete(
                        widgetId = input.noHourlyWidgetId,
                        dateStr = input.noHourlyDate!!,
                        lat = input.noHourlyLat,
                        lon = input.noHourlyLon,
                    )
                }
            }
        }

        // ---- widget painting ----

        /**
         * Paints the no-location state on every placed widget and logs it. Returns [Result.success]
         * so the caller can `return renderNoLocationAndFinish(...)` — this is a settled state, not a
         * transient failure, so retrying would only burn wakeups until the user acts or device
         * following lands a fix.
         *
         * Deliberately does *not* honour the screen-off paint skip that [updateAllWidgets] applies.
         * That skip is a battery optimisation for repeated data repaints; here it would strand a
         * first-ever run behind the "Loading..." placeholder indefinitely.
         */
        private suspend fun renderNoLocationAndFinish(reason: String): Result {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            appLogDao.log(
                "NO_LOCATION",
                "reason=$reason widgets=${appWidgetIds.size} action=render_error_skip_fetch",
                "INFO",
            )
            appWidgetIds.forEach { appWidgetId ->
                WidgetRenderer.updateWidgetNoLocation(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                )
            }
            return Result.success()
        }

        private suspend fun updateAllWidgets(
            weatherList: List<ForecastEntity>,
            forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
            hourlyForecasts: List<HourlyForecastEntity>,
            currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
            dailyActuals: DailyActualsBySource = emptyMap(),
            jobType: WidgetUpdateTracker.JobType = WidgetUpdateTracker.JobType.BACKGROUND_SYNC,
            uiOnly: Boolean = false,
            origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
            loadedActualsSourceIds: Collection<String> = emptyList(),
            reloadActuals: (suspend (List<String>) -> DailyActualsBySource)? = null,
        ) = coroutineScope {
            if (!isScreenInteractive()) {
                appLogDao.log("WIDGET_PAINT_SKIP", "reason=screen_off", "INFO")
                return@coroutineScope
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val effectiveActuals = resolveEffectiveActuals(
                appWidgetIds = appWidgetIds,
                loadedActualsSourceIds = loadedActualsSourceIds,
                reloadActuals = reloadActuals,
                dailyActuals = dailyActuals,
            )

            val effectiveOrigin = if (uiOnly) WidgetPushDispatcher.Origin.UI_ONLY else origin
            for (appWidgetId in appWidgetIds) {
                if (shouldSkipWidgetRender(appWidgetId)) {
                    Log.v(TAG, "Skipping render for widget $appWidgetId (throttled)")
                    continue
                }
                val job = launch {
                    WidgetRenderer.updateWidgetWithData(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        weatherList = weatherList,
                        forecastSnapshots = forecastSnapshots,
                        hourlyForecasts = hourlyForecasts,
                        currentTemps = currentTemps,
                        dailyActualsBySource = effectiveActuals,
                        repository = weatherRepository,
                        uiOnly = uiOnly,
                        partialPush = true,
                        origin = effectiveOrigin,
                    )
                }
                WidgetUpdateTracker.trackJob(appWidgetId, job, jobType)
                lastRenderMs[appWidgetId] = SystemClock.elapsedRealtime()
            }
        }

        private suspend fun resolveEffectiveActuals(
            appWidgetIds: IntArray,
            loadedActualsSourceIds: Collection<String>,
            reloadActuals: (suspend (List<String>) -> DailyActualsBySource)?,
            dailyActuals: DailyActualsBySource,
        ): DailyActualsBySource {
            val paintSourceIds = appWidgetIds.map { widgetStateManager.getCurrentDisplaySource(it).id }.distinct()
            val uncoveredSources = DailyActualsCoverage.uncoveredSources(paintSourceIds, loadedActualsSourceIds)
            if (uncoveredSources.isEmpty() || reloadActuals == null) return dailyActuals

            appLogDao.log(
                "ACTUALS_SOURCE_RACE",
                "uncovered=${uncoveredSources.joinToString(",")} " +
                    "loaded=${loadedActualsSourceIds.joinToString(",")} " +
                    "paint=${paintSourceIds.joinToString(",")} reloading",
                "INFO",
            )
            return reloadActuals(
                DailyActualsCoverage.unionSourceIds(paintSourceIds, loadedActualsSourceIds),
            ).takeIf { it.isNotEmpty() } ?: dailyActuals
        }

        private fun shouldSkipWidgetRender(appWidgetId: Int): Boolean {
            val last = lastRenderMs[appWidgetId] ?: return false
            return SystemClock.elapsedRealtime() - last < MIN_RENDER_INTERVAL_MS
        }

        private suspend fun refreshWidgetsFromCache() {
            val location = ActiveLocationResolver.resolve(
                context, widgetStateManager, WeatherDatabase.getDatabase(context).forecastDao(),
            ) ?: run {
                renderNoLocationAndFinish("refresh_from_cache")
                return
            }
            val bundle = dataBundleLoader.load(
                latitude = location.first,
                longitude = location.second,
                networkAllowed = false,
                recomputeActuals = false,
                forceRefresh = false,
                targetSourceId = null,
                fetchContext = null,
            )
            updateAllWidgets(
                weatherList = bundle.weatherList,
                forecastSnapshots = bundle.forecastSnapshots,
                hourlyForecasts = bundle.hourlyForecasts,
                currentTemps = bundle.currentTemps,
                dailyActuals = bundle.dailyActuals,
                jobType = WidgetUpdateTracker.JobType.BACKGROUND_SYNC,
                origin = WidgetPushDispatcher.Origin.WORKER_CACHE,
                loadedActualsSourceIds = bundle.activeSourceIds,
                reloadActuals = { sourceIds ->
                    dataBundleLoader.reloadDailyActuals(
                        lat = location.first,
                        lon = location.second,
                        hourlyForecasts = bundle.hourlyForecasts,
                        sourceIds = sourceIds,
                    )
                },
            )
        }

        // ---- lifecycle helpers ----

        private suspend fun manageCurrentTempLoopAfterRun(
            device: DeviceContext,
            ignoreRunningWorkId: java.util.UUID? = null,
        ) {
            when (CurrentTempFetchPolicy.postRunLoopAction(device.isCharging, device.isScreenInteractive)) {
                CurrentTempFetchPolicy.PostRunLoopAction.SCHEDULE_NEXT ->
                    CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                        context = context,
                        workManager = WorkManager.getInstance(context),
                        nowMs = System.currentTimeMillis(),
                        ignoreRunningWorkId = ignoreRunningWorkId,
                        isScreenInteractive = device.isScreenInteractive,
                    )
                CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE ->
                    appLogDao.log(
                        "CURR_FETCH_LOOP_STOP",
                        "reason=policy_blocked plugged=${device.isCharging} interactive=${device.isScreenInteractive} action=no_reschedule",
                        "INFO",
                    )
            }
        }

        private suspend fun manageNonPrimaryLoopAfterRun(device: DeviceContext) {
            val intervalMinutes = ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes(
                device.isCharging, device.isScreenInteractive,
            )
            if (intervalMinutes != null) {
                NonPrimaryObservationScheduler.scheduleNextUpdate(
                    context = context,
                    isScreenInteractive = device.isScreenInteractive,
                )
            } else {
                appLogDao.log(
                    "NONPRIMARY_LOOP_STOP",
                    "reason=policy_blocked plugged=${device.isCharging} interactive=${device.isScreenInteractive}",
                    "INFO",
                )
            }
        }

        private fun broadcastNoHourlyRefreshComplete(
            widgetId: Int,
            dateStr: String,
            lat: Double,
            lon: Double,
        ) {
            val completeIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_NO_HOURLY_REFRESH_COMPLETE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra("date", dateStr)
                putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LAT, lat)
                putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LON, lon)
            }
            context.sendBroadcast(completeIntent)
            Log.d(TAG, "broadcastNoHourlyRefreshComplete: widget=$widgetId date=$dateStr")
        }

        // ---- diagnostics ----

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
                        .putBoolean(KEY_FORCE_REFRESH, true)
                        .putString(KEY_CURRENT_TEMP_REASON, "debug_fast_refresh")
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

        private fun measureDeviceContext(): DeviceContext {
            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val isPlugged = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
            val isScreenInteractive = isScreenInteractive()
            val lastFullFetchMs = weatherRepository.lastNetworkFetchTimeMs
            val lastFullFetchAge = if (lastFullFetchMs > 0) (System.currentTimeMillis() - lastFullFetchMs) / 1000 else -1
            return DeviceContext(
                isCharging = isPlugged,
                batteryLevel = batteryLevel,
                isScreenInteractive = isScreenInteractive,
                lastFullFetchAgeSeconds = lastFullFetchAge,
            )
        }

        private fun isScreenInteractive(): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return powerManager.isInteractive
        }

        /**
         * Never fabricates a place name. This used to answer "Mountain View, CA" for the hard-default
         * coordinates, which is how a user with no resolvable location ended up seeing Google HQ's
         * weather labelled as their own. A coordinate string is the honest fallback.
         */
        private fun getLocationName(lat: Double, lon: Double): String =
            com.weatherwidget.util.FriendlyLocationName.cached(context, lat, lon)
                ?: "%.2f, %.2f".format(lat, lon)

        // ---- constants ----

        companion object {
            private const val TAG = "WeatherWidgetWorker"
            private const val MIN_RENDER_INTERVAL_MS = 30_000L

            // No DEFAULT_LAT/DEFAULT_LON. "No location" is the absence of coordinates, not a
            // stand-in for one; the retired Google-HQ values survive only in
            // LegacyDefaultLocationMigration, which erases them from upgrading installs.
            const val KEY_UI_ONLY_REFRESH = "ui_only_refresh"
            const val KEY_FORCE_REFRESH = "force_refresh"
            const val KEY_LOCATION_CANDIDATE_REFRESH = "location_candidate_refresh"
            const val KEY_CURRENT_TEMP_ONLY = "current_temp_only"
            const val KEY_NONPRIMARY_CURRENT_TEMP_ONLY = "nonprimary_current_temp_only"
            const val KEY_CURRENT_TEMP_OPPORTUNISTIC = "current_temp_opportunistic"
            const val KEY_CURRENT_TEMP_REASON = "current_temp_reason"
            const val KEY_TARGET_SOURCE = "target_source"
            const val KEY_OBSERVATION_BACKFILL_ONLY = "observation_backfill_only"
            const val KEY_OBSERVATION_BACKFILL_HOURS = "observation_backfill_hours"
            const val KEY_OBSERVATION_BACKFILL_REASON = "observation_backfill_reason"
            const val KEY_BACKFILL_LAT = "backfill_lat"
            const val KEY_BACKFILL_LON = "backfill_lon"
            const val KEY_NO_HOURLY_WIDGET_ID = "no_hourly_widget_id"
            const val KEY_NO_HOURLY_DATE = "no_hourly_date"
            const val KEY_NO_HOURLY_LAT = "no_hourly_lat"
            const val KEY_NO_HOURLY_LON = "no_hourly_lon"
            const val DEFAULT_OBSERVATION_BACKFILL_HOURS = 72L
            const val WORK_NAME_LOCATION_CANDIDATE = "weather_widget_location_candidate"
            const val WORK_NAME_DEBUG_FAST_REFRESH = "weather_widget_debug_fast_refresh"

            private const val DEBUG_FAST_FULL_REFRESH_SECONDS = 0L
        }
    }
