package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
        private val painter by lazy {
            WidgetPaintCoordinator(context, weatherRepository, widgetStateManager, appLogDao, hourlyForecastLoader, dataBundleLoader)
        }
        private val fullSyncPipeline by lazy {
            FullSyncPipeline(context, weatherRepository, widgetStateManager, appLogDao, gpsResampler, hourlyForecastLoader, dataBundleLoader, painter)
        }

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

            // No global "just fetched, skip" gate here. Per-source freshness is enforced one layer
            // down, in ForecastRepository.getWeatherData → ForecastFetchCoordinator.requiresNetworkFetch
            // (per-source isStale against the same fetchContext). A global timestamp can defer a
            // genuinely stale source for the whole cooldown window (code-review M4).

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

            return fullSyncPipeline.run(input, device, stopReason)
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
                painter.refreshWidgetsFromCache()
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
                        ?: return painter.renderNoLocationAndFinish("current_temp_only")
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
                    painter.refreshWidgetsFromCache()
                    cacheRefreshDurationMs = SystemClock.elapsedRealtime() - cacheRefreshStartMs
                }

                WidgetLoopScheduler.manageCurrentTempLoopAfterRun(context, appLogDao, device, ignoreRunningWorkId = id)

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
                WidgetLoopScheduler.manageCurrentTempLoopAfterRun(context, appLogDao, device, ignoreRunningWorkId = id)
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
                        ?: return painter.renderNoLocationAndFinish("non_primary_current_temp")
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
                painter.refreshWidgetsFromCache()
                val cacheRefreshDurationMs = SystemClock.elapsedRealtime() - cacheRefreshStartMs

                WidgetLoopScheduler.manageNonPrimaryLoopAfterRun(context, appLogDao, device)

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
                WidgetLoopScheduler.manageNonPrimaryLoopAfterRun(context, appLogDao, device)
                Result.retry()
            }
        }

        // ---- diagnostics ----

        private fun measureDeviceContext(): DeviceContext {
            val snapshot = BatterySnapshotProvider.snapshot(context)
            val isScreenInteractive = isScreenInteractive()
            val lastFullFetchMs = weatherRepository.lastNetworkFetchTimeMs
            val lastFullFetchAge = if (lastFullFetchMs > 0) (System.currentTimeMillis() - lastFullFetchMs) / 1000 else -1
            return DeviceContext(
                isCharging = snapshot.isCharging,
                batteryLevel = snapshot.batteryLevel,
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
        }
    }
