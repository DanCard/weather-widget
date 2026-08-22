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
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import kotlinx.coroutines.CancellationException
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
) {
    suspend fun run(input: WorkInput, device: DeviceContext, stopReason: Int): ListenableWorker.Result {
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
                ?: return painter.renderNoLocationAndFinish("full_sync")
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
                            -> return@fold ListenableWorker.Result.success()
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
                    val afterActualsMs = SystemClock.elapsedRealtime()

                    if (!input.uiOnlyRefresh) {
                        weatherRepository.ensureForecastOnlyHistoryRows(location.first, location.second)
                        weatherRepository.snapshotDisplayedRainChance(location.first, location.second)
                        weatherRepository.backfillForecastChanceSnapshotsIfNeeded(location.first, location.second)
                        weatherRepository.backfillFrozenDisplayColumnsIfNeeded(location.first, location.second)
                        weatherRepository.repairFrozenRainChanceIfNeeded(location.first, location.second)
                    }

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

                    appLogDao.log(
                        "WIDGET_LIFECYCLE",
                        "phase=worker_paint_start uiOnly=${input.uiOnlyRefresh} thread=${Thread.currentThread().name}",
                    )
                    val jobType = if (input.uiOnlyRefresh) WidgetUpdateTracker.JobType.UI_PAINT else WidgetUpdateTracker.JobType.BACKGROUND_SYNC
                    val workerOrigin = if (input.uiOnlyRefresh) WidgetPushDispatcher.Origin.UI_ONLY else WidgetPushDispatcher.Origin.WORKER_FETCH
                    painter.updateAllWidgets(
                        weatherList = weatherList,
                        forecastSnapshots = forecastSnapshots,
                        hourlyForecasts = renderHourlyForecasts,
                        currentTemps = currentTemps,
                        dailyActuals = dailyActuals,
                        jobType = jobType,
                        uiOnly = input.uiOnlyRefresh,
                        origin = workerOrigin,
                        loadedActualsSourceIds = actualsSourceList,
                        loadedHourlySourceIds = renderHourlySourceIds,
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
                        WidgetLoopScheduler.manageCurrentTempLoopAfterRun(context, appLogDao, device)
                    }

                    ListenableWorker.Result.success()
                },
                onFailure = { e ->
                    appLogDao.log("SYNC_FAILURE", "Repository failed: ${e.message}", "ERROR")
                    ListenableWorker.Result.retry()
                },
            )
        } catch (e: CancellationException) {
            appLogDao.log("SYNC_CANCELLED", "Worker cancelled. stopReason=$stopReason msg=${e.message}", "INFO")
            throw e
        } catch (e: Exception) {
            appLogDao.logException("SYNC_EXCEPTION", "Synchronization failed", e)
            return ListenableWorker.Result.retry()
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
