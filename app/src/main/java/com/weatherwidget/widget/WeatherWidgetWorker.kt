package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.DailyActualsBySource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
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
    ) : CoroutineWorker(context, workerParams) {
        override suspend fun doWork(): Result {
            if (WeatherDatabase.isTestingMode()) {
                Log.d(TAG, "Skipping worker execution in test mode")
                return Result.success()
            }

            val uiOnlyRefresh = inputData.getBoolean(KEY_UI_ONLY_REFRESH, false)
            val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
            val currentTempOnly = inputData.getBoolean(KEY_CURRENT_TEMP_ONLY, false)
            val opportunisticCurrentTemp = inputData.getBoolean(KEY_CURRENT_TEMP_OPPORTUNISTIC, false)
            val currentTempReason = inputData.getString(KEY_CURRENT_TEMP_REASON) ?: "unspecified"
            val targetSourceId = inputData.getString(KEY_TARGET_SOURCE)
            val observationBackfillMode = inputData.getBoolean(KEY_OBSERVATION_BACKFILL_ONLY, false)
            val backfillLat = inputData.getDouble(KEY_BACKFILL_LAT, DEFAULT_LAT)
            val backfillLon = inputData.getDouble(KEY_BACKFILL_LON, DEFAULT_LON)
            val backfillHours = inputData.getLong(KEY_OBSERVATION_BACKFILL_HOURS, DEFAULT_OBSERVATION_BACKFILL_HOURS)
            val backfillReason = inputData.getString(KEY_OBSERVATION_BACKFILL_REASON) ?: "unspecified"
            val forecastDays = inputData.getInt(KEY_FORECAST_DAYS, ForecastHorizon.BASELINE_DAYS)

            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val physicalPlugged = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) > 0
            val isPlugged = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
            val isScreenInteractive = isScreenInteractive()

            val lastFullFetchMs = weatherRepository.lastNetworkFetchTimeMs
            val lastFullFetchAge = if (lastFullFetchMs > 0) (System.currentTimeMillis() - lastFullFetchMs) / 1000 else -1
            appLogDao.log(
                "SYNC_START",
                "uiOnly=$uiOnlyRefresh, force=$forceRefresh, currentOnly=$currentTempOnly, " +
                    "opportunistic=$opportunisticCurrentTemp, battery=$batteryLevel%, plugged=$isPlugged, physicalPlugged=$physicalPlugged, " +
                    "interactive=$isScreenInteractive, reason=$currentTempReason, " +
                    "obsBackfillOnly=$observationBackfillMode, lastFullFetch=${lastFullFetchAge}s ago",
            )

            // Cooldown: skip full background syncs if one finished very recently (last 5 mins)
            // Does not apply to forced (user-triggered) or UI-only updates.
            if (!forceRefresh && !uiOnlyRefresh && !currentTempOnly && !observationBackfillMode && lastFullFetchAge in 0..300) {
                appLogDao.log("SYNC_SKIP", "reason=cooldown age=${lastFullFetchAge}s", "INFO")
                return Result.success()
            }

            if (observationBackfillMode) {
                return handleObservationBackfillWork(
                    latitude = backfillLat,
                    longitude = backfillLon,
                    lookbackHours = backfillHours,
                    reason = backfillReason,
                )
            }

            if (currentTempOnly) {
                return handleCurrentTempOnlyWork(
                    isPlugged = isPlugged,
                    isScreenInteractive = isScreenInteractive,
                    isOpportunisticContext = opportunisticCurrentTemp,
                    reason = currentTempReason,
                    force = forceRefresh,
                    targetSource = targetSourceId?.let(WeatherSource::fromId),
                )
            }



            return try {
                val startMs = SystemClock.elapsedRealtime()

                // Build per-source fetch context up front so the repository can decide which
                // sources are due according to ForecastFetchPolicy (charging/screen/active-aware).
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                val stateManager = WidgetStateManager(context)

                // The widget's CONFIGURED location (set via GPS/zip in ConfigActivity or Settings)
                // must drive the fetch. Previously we used getLatestLocation() = the location of the
                // most recent weather row, which decoupled the fetch from the configured location:
                // once the table seeded to the default, every refresh re-fetched the default and the
                // configured location (used only for rendering) was never honored — pinning the
                // widget to the wrong place permanently.
                val configuredLocation = appWidgetIds.toList().firstNotNullOfOrNull { id -> stateManager.getWidgetLocation(id) }
                val location =
                    configuredLocation
                        ?: weatherRepository.getLatestLocation()
                        ?: (DEFAULT_LAT to DEFAULT_LON)
                Log.d(TAG, "doWork: Location = $location (configured=${configuredLocation != null})")
                val activeSourceList = appWidgetIds.map { id ->
                    stateManager.getCurrentDisplaySource(id).id
                }.distinct()
                val fetchContext = if (!forceRefresh && !uiOnlyRefresh) {
                    ForecastFetchContext(
                        isCharging = isPlugged,
                        isScreenInteractive = isScreenInteractive,
                        batteryLevel = batteryLevel,
                        activeSourceIds = activeSourceList.toSet(),
                    )
                } else null

                val result =
                    weatherRepository.getWeatherData(
                        latitude = location.first,
                        longitude = location.second,
                        locationName = getLocationName(location.first, location.second),
                        forceRefresh = forceRefresh && !uiOnlyRefresh,
                        networkAllowed = WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh),
                        targetSourceId = targetSourceId,
                        fetchContext = fetchContext,
                        forecastDays = forecastDays,
                    )

                result.fold(
                    onSuccess = { weatherList ->
                        val afterWeatherMs = SystemClock.elapsedRealtime()
                        Log.d(TAG, "doWork: Got ${weatherList.size} weather entries")

                        // Fetch forecast snapshots for comparison
                        val forecastSnapshots = fetchForecastSnapshots(location.first, location.second)
                        val hourlyForecasts = fetchHourlyForecasts(location.first, location.second)
                        val afterHourlyMs = SystemClock.elapsedRealtime()

                        // Backfill NWS history if this is a new location or no history exists
                        // ONLY perform if not a UI-only refresh to avoid blocking during frequent updates
                        if (!uiOnlyRefresh && (targetSourceId == com.weatherwidget.data.model.WeatherSource.NWS.id || (targetSourceId == null && weatherList.any { it.source == com.weatherwidget.data.model.WeatherSource.NWS.id }))) {
                            Log.d(TAG, "doWork: Triggering NWS backfill check")
                            weatherRepository.backfillNwsObservationsIfNeeded(location.first, location.second)
                        }
                        val afterBackfillMs = SystemClock.elapsedRealtime()

                        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
                            location.first,
                            location.second,
                            todayStartMs,
                        )

                        appLogDao.log("SYNC_SUCCESS", "Weather=${weatherList.size}, Snapshots=${forecastSnapshots.size}, Hourly=${hourlyForecasts.size}", "INFO")

                        val dailyActuals = fetchDailyActuals(
                            lat = location.first,
                            lon = location.second,
                            hourlyForecasts = hourlyForecasts,
                            activeSourceList = activeSourceList,
                            recompute = !uiOnlyRefresh
                        )
                        val afterActualsMs = SystemClock.elapsedRealtime()

                        appLogDao.log("WIDGET_LIFECYCLE", "phase=worker_paint_start uiOnly=$uiOnlyRefresh thread=${Thread.currentThread().name}")
                        val jobType = if (uiOnlyRefresh) WidgetUpdateTracker.JobType.UI_PAINT else WidgetUpdateTracker.JobType.BACKGROUND_SYNC
                        updateAllWidgets(weatherList, forecastSnapshots, hourlyForecasts, currentTemps, dailyActuals, jobType, uiOnly = uiOnlyRefresh)
                        val afterUpdateMs = SystemClock.elapsedRealtime()
                        appLogDao.log("WIDGET_LIFECYCLE", "phase=worker_paint_done uiOnly=$uiOnlyRefresh elapsedMs=${afterUpdateMs - afterActualsMs}")

                        val totalMs = afterUpdateMs - startMs
                        if (totalMs > 500) {
                            appLogDao.log(
                                "SYNC_PERF",
                                "uiOnly=$uiOnlyRefresh total=${totalMs}ms " +
                                    "weather=${afterWeatherMs - startMs}ms " +
                                    "hourly=${afterHourlyMs - afterWeatherMs}ms " +
                                    "backfill=${afterBackfillMs - afterHourlyMs}ms " +
                                    "actuals=${afterActualsMs - afterBackfillMs}ms " +
                                    "widgets=${afterUpdateMs - afterActualsMs}ms"
                            )
                        }

                        if (!uiOnlyRefresh) {
                            val uiScheduler = UIUpdateScheduler(context)
                            uiScheduler.scheduleNextUpdate()
                        } else {
                            // Even on UI-only, ensure heartbeats are alive
                            manageCurrentTempLoopAfterRun(isPlugged, isScreenInteractive)
                        }
                        Result.success()
                    },
                    onFailure = { e ->
                        appLogDao.log("SYNC_FAILURE", "Repository failed: ${e.message}", "ERROR")
                        Result.retry()
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                val reasonMsg = "Worker cancelled. stopReason=$stopReason msg=${e.message}"
                appLogDao.log("SYNC_CANCELLED", reasonMsg, "INFO")
                throw e
            } catch (e: Exception) {
                appLogDao.logException("SYNC_EXCEPTION", "Synchronization failed", e)
                Result.retry()
            }
        }

        private suspend fun fetchForecastSnapshots(
            lat: Double,
            lon: Double,
        ): Map<LocalDate, List<ForecastEntity>> {
            return try {
                val today = LocalDate.now()
                val pastStart = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
                val pastEnd = today.minusDays(2).toEpochDay() * WidgetConstants.MS_IN_A_DAY
                val recentStart = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
                val recentEnd = today.plusDays(7).toEpochDay() * WidgetConstants.MS_IN_A_DAY

                val pastSnapshots = weatherRepository.getLatestForecastsInRange(pastStart, pastEnd, lat, lon)
                val recentSnapshots = weatherRepository.getAllForecastsInRange(recentStart, recentEnd, lat, lon)
                (pastSnapshots + recentSnapshots).groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch forecast snapshots", e)
                emptyMap()
            }
        }

        private suspend fun fetchDailyActuals(
            lat: Double,
            lon: Double,
            hourlyForecasts: List<HourlyForecastEntity>,
            activeSourceList: List<String>,
            recompute: Boolean = true,
        ): DailyActualsBySource {
            return try {
                if (recompute) {
                    val start = LocalDate.now().minusDays(30)
                    val yesterday = LocalDate.now().minusDays(1)
                    weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, start, yesterday, hourlyForecasts)
                }
                weatherRepository.getDailyActualsWithLiveToday(lat, lon, hourlyForecasts, activeSourceList)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch daily actuals", e)
                emptyMap()
            }
        }

        private suspend fun fetchHourlyForecasts(
            lat: Double,
            lon: Double,
        ): List<HourlyForecastEntity> {
            return try {
                val database = WeatherDatabase.getDatabase(context)
                val hourlyDao = database.hourlyForecastDao()
                val historyDao = database.hourlyForecastHistoryDao()
                val now = LocalDateTime.now()
                val zoneId = ZoneId.systemDefault()
                // Extended range for hourly view and rain analysis: 72h past to 168h future (today + 7 days)
                // Must cover the full daily forecast range so the hourly graph works for any tapped day.
                val startTimeMs = now.minusHours(72).atZone(zoneId).toInstant().toEpochMilli()
                val endTimeMs = now.plusHours(168).atZone(zoneId).toInstant().toEpochMilli()
                Log.d(TAG, "fetchHourlyForecasts: range=${now.minusHours(72)} to ${now.plusHours(168)} (ms=$startTimeMs to $endTimeMs)")
                val current = hourlyDao.getHourlyForecasts(startTimeMs, endTimeMs, lat, lon)
                // The proximity query may return rows from multiple nearby locations (e.g. 37.422 and
                // 37.4168). Pin to the single closest location so the stitched list is single-location,
                // matching the strict filter WidgetRenderer applies downstream.
                val bestLat: Double
                val bestLon: Double
                val bestPair = current.asSequence()
                    .map { it.locationLat to it.locationLon }
                    .distinct()
                    .minByOrNull { (rowLat, rowLon) ->
                        Math.abs(rowLat - lat) + Math.abs(rowLon - lon)
                    }
                if (bestPair != null) {
                    bestLat = bestPair.first
                    bestLon = bestPair.second
                } else {
                    bestLat = lat
                    bestLon = lon
                }
                // Keep every row at the SAME physical site as bestPair (not exact float equality):
                // one site fragments into sub-precision coordinates across fetches, and dropping the
                // fragments here would blank part of the graph downstream. See LocationMatch.sameSite.
                val filteredCurrent = if (bestPair != null) {
                    current.filter { LocationMatch.sameSite(it.locationLat, it.locationLon, bestLat, bestLon) }
                } else current
                val history = historyDao.getHistoryInRangeForBucketWindowAllSources(
                    startDateTime = startTimeMs,
                    endDateTime = endTimeMs,
                    bucketStart = Long.MIN_VALUE,
                    bucketEnd = Long.MAX_VALUE,
                    lat = bestLat,
                    lon = bestLon,
                ).filter { LocationMatch.sameSite(it.locationLat, it.locationLon, bestLat, bestLon) }
                    .map {
                        HourlyForecastEntity(
                            dateTime = it.dateTime,
                            locationLat = it.locationLat,
                            locationLon = it.locationLon,
                            temperature = it.temperature,
                            condition = it.condition,
                            source = it.source,
                            precipProbability = it.precipProbability,
                            cloudCover = it.cloudCover,
                            precipAmountMm = it.precipAmountMm,
                            fetchedAt = it.fetchedAt,
                        )
                    }
                val stitched = (history + filteredCurrent)
                    .associateBy { Pair(it.dateTime, it.source) }
                    .values
                    .sortedBy { it.dateTime }
                if (stitched.size != filteredCurrent.size) {
                    Log.i(TAG, "fetchHourlyForecasts: stitched ${stitched.size - filteredCurrent.size} missing rows from history (bestLoc=$bestLat,$bestLon)")
                }
                stitched
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch hourly forecasts", e)
                emptyList()
            }
        }

        private suspend fun updateAllWidgets(
            weatherList: List<ForecastEntity>,
            forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
            hourlyForecasts: List<HourlyForecastEntity>,
            currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
            dailyActuals: DailyActualsBySource = emptyMap(),
            jobType: WidgetUpdateTracker.JobType = WidgetUpdateTracker.JobType.BACKGROUND_SYNC,
            uiOnly: Boolean = false,
        ) = coroutineScope {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                val job = launch {
                    WidgetRenderer.updateWidgetWithData(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        weatherList = weatherList,
                        forecastSnapshots = forecastSnapshots,
                        hourlyForecasts = hourlyForecasts,
                        currentTemps = currentTemps,
                        dailyActualsBySource = dailyActuals,
                        repository = weatherRepository,
                        uiOnly = uiOnly,
                    )
                }
                WidgetUpdateTracker.trackJob(appWidgetId, job, jobType)
            }
        }

        private suspend fun handleCurrentTempOnlyWork(
            isPlugged: Boolean,
            isScreenInteractive: Boolean,
            isOpportunisticContext: Boolean,
            reason: String,
            force: Boolean = false,
            targetSource: WeatherSource? = null,
        ): Result {
            val startMs = SystemClock.elapsedRealtime()
            appLogDao.log("CURR_FETCH_WORK_START", "id=$id reason=$reason isPlugged=$isPlugged isInteractive=$isScreenInteractive opportunistic=$isOpportunisticContext")
            
            return try {
                val isManual = reason.contains("manual") || reason.contains("force") || force
                var resultMessage = "success"
                var fetchDurationMs = 0L
                if (
                    !CurrentTempFetchPolicy.shouldFetchNow(
                        isCharging = isPlugged,
                        isScreenInteractive = isScreenInteractive,
                        isOpportunisticContext = isOpportunisticContext,
                        isManual = isManual,
                    )
                ) {
                    appLogDao.log(
                        "CURR_FETCH_SKIP",
                        "reason=$reason policy_blocked charging=$isPlugged interactive=$isScreenInteractive opportunistic=$isOpportunisticContext",
                        "INFO",
                    )
                    resultMessage = "skipped_policy_blocked"
                } else {
                    val location = weatherRepository.getLatestLocation() ?: (DEFAULT_LAT to DEFAULT_LON)
                    val fetchStartMs = SystemClock.elapsedRealtime()
                    val refreshResult =
                        weatherRepository.refreshCurrentTemperature(
                            latitude = location.first,
                            longitude = location.second,
                            locationName = getLocationName(location.first, location.second),
                            source = targetSource,
                            reason = reason,
                            forceRefresh = force,
                        )
                    fetchDurationMs = SystemClock.elapsedRealtime() - fetchStartMs

                    refreshResult.fold(
                        onSuccess = { _ ->
                            // Done log is handled by repository now
                            resultMessage = "success"
                        },
                        onFailure = { e ->
                            appLogDao.log("CURR_FETCH_FAIL", "reason=$reason ${e.message}", "ERROR")
                            resultMessage = "fetch_failure:${e.message}"
                        },
                    )
                }

                val cacheRefreshStartMs = SystemClock.elapsedRealtime()
                refreshWidgetsFromCache()
                val cacheRefreshDurationMs = SystemClock.elapsedRealtime() - cacheRefreshStartMs
                
                manageCurrentTempLoopAfterRun(isPlugged, isScreenInteractive, ignoreRunningWorkId = id)
                
                val totalDurationMs = SystemClock.elapsedRealtime() - startMs
                if (totalDurationMs > 500) {
                    appLogDao.log(
                        "SYNC_PERF_CURRENT",
                        "reason=$reason total=${totalDurationMs}ms fetch=${fetchDurationMs}ms widgets=${cacheRefreshDurationMs}ms",
                        "INFO"
                    )
                }

                appLogDao.log(
                    "CURR_FETCH_WORK_RESULT",
                    "id=$id reason=$reason result=$resultMessage",
                    "INFO",
                )
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                val reasonMsg = "CurrentTemp fetch cancelled. reason=$reason stopReason=$stopReason durationMs=$durationMs msg=${e.message}"
                appLogDao.log("CURR_FETCH_CANCELLED", reasonMsg, "INFO")
                throw e
            } catch (e: Exception) {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                appLogDao.logException("CURR_FETCH_EXCEPTION", "CurrentTemp fetch failed (reason=$reason, duration=${durationMs}ms)", e)
                manageCurrentTempLoopAfterRun(isPlugged, isScreenInteractive, ignoreRunningWorkId = id)
                Result.retry()
            }
        }

        private suspend fun handleObservationBackfillWork(
            latitude: Double,
            longitude: Double,
            lookbackHours: Long,
            reason: String,
        ): Result {
            return try {
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_RUN",
                    "reason=$reason lat=$latitude lon=$longitude lookbackHours=$lookbackHours",
                    "INFO",
                )
                val result = weatherRepository.backfillRecentNwsObservations(latitude, longitude, lookbackHours)
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_RESULT",
                    "reason=$reason stations=${result.stationsTried} rows=${result.rowsFetched} affectedDates=${result.affectedDates.sorted()}",
                    "INFO",
                )
                refreshWidgetsFromCache()
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                val reasonMsg = "Observation backfill cancelled. reason=$reason stopReason=$stopReason msg=${e.message}"
                appLogDao.log("OBS_BACKFILL_CANCELLED", reasonMsg, "INFO")
                throw e
            } catch (e: Exception) {
                appLogDao.logException("OBS_HOURLY_BACKFILL_EXCEPTION", "Observation backfill failed (reason=$reason)", e)
                Result.failure()
            }
        }

        private suspend fun refreshWidgetsFromCache() {
            val location = weatherRepository.getLatestLocation() ?: (DEFAULT_LAT to DEFAULT_LON)
            val weatherList =
                weatherRepository.getWeatherData(
                    latitude = location.first,
                    longitude = location.second,
                    locationName = getLocationName(location.first, location.second),
                    networkAllowed = false,
                ).getOrDefault(emptyList())
            val forecastSnapshots = fetchForecastSnapshots(location.first, location.second)
            val hourlyForecasts = fetchHourlyForecasts(location.first, location.second)

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            val stateManager = WidgetStateManager(context)
            val activeSourceList = appWidgetIds.map { id ->
                stateManager.getCurrentDisplaySource(id).id
            }.distinct()

            val dailyActuals = fetchDailyActuals(
                lat = location.first,
                lon = location.second,
                hourlyForecasts = hourlyForecasts,
                activeSourceList = activeSourceList,
                recompute = false
            )
            val todayStartMs2 = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
                location.first,
                location.second,
                todayStartMs2,
            )
            updateAllWidgets(weatherList, forecastSnapshots, hourlyForecasts, currentTemps, dailyActuals, WidgetUpdateTracker.JobType.BACKGROUND_SYNC)
        }

        private suspend fun manageCurrentTempLoopAfterRun(
            isPlugged: Boolean,
            isScreenInteractive: Boolean,
            ignoreRunningWorkId: java.util.UUID? = null,
        ) {
            when (CurrentTempFetchPolicy.postRunLoopAction(isPlugged, isScreenInteractive)) {
                CurrentTempFetchPolicy.PostRunLoopAction.SCHEDULE_NEXT ->
                    CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                        context = context,
                        workManager = WorkManager.getInstance(context),
                        nowMs = System.currentTimeMillis(),
                        ignoreRunningWorkId = ignoreRunningWorkId,
                        isScreenInteractive = isScreenInteractive,
                    )
                // On battery we must NOT cancel here: cancel() targets the unique work name
                // WORK_NAME_CURRENT_TEMP, and an opportunistic current-temp fetch can be running
                // concurrently under that same name (OpportunisticUpdateJobService enqueues a
                // UI-only worker and a fetch worker together). Cancelling by name truncated that
                // fetch mid-flight — the root cause of current temp being slow to refresh on
                // battery. The loop instead dies by not rescheduling; ScreenOnReceiver handles
                // prompt teardown on unplug. See CurrentTempFetchPolicy.PostRunLoopAction.
                CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE ->
                    appLogDao.log(
                        "CURR_FETCH_LOOP_STOP",
                        "reason=policy_blocked plugged=$isPlugged interactive=$isScreenInteractive action=no_reschedule",
                        "INFO",
                    )
            }
        }

        private fun isScreenInteractive(): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return powerManager.isInteractive
        }

        private fun getLocationName(
            lat: Double,
            lon: Double,
        ): String {
            return if (lat == DEFAULT_LAT && lon == DEFAULT_LON) {
                "Mountain View, CA"
            } else {
                "%.2f, %.2f".format(lat, lon)
            }
        }

        companion object {
            private const val TAG = "WeatherWidgetWorker"
            const val DEFAULT_LAT = 37.4220
            const val DEFAULT_LON = -122.0841
            const val KEY_UI_ONLY_REFRESH = "ui_only_refresh"
            const val KEY_FORCE_REFRESH = "force_refresh"
            const val KEY_CURRENT_TEMP_ONLY = "current_temp_only"
            const val KEY_CURRENT_TEMP_OPPORTUNISTIC = "current_temp_opportunistic"
            const val KEY_CURRENT_TEMP_REASON = "current_temp_reason"
            const val KEY_TARGET_SOURCE = "target_source"
            const val KEY_OBSERVATION_BACKFILL_ONLY = "observation_backfill_only"
            const val KEY_OBSERVATION_BACKFILL_HOURS = "observation_backfill_hours"
            const val KEY_OBSERVATION_BACKFILL_REASON = "observation_backfill_reason"
            const val KEY_BACKFILL_LAT = "backfill_lat"
            const val KEY_BACKFILL_LON = "backfill_lon"
            const val KEY_FORECAST_DAYS = "forecast_days"
            const val DEFAULT_OBSERVATION_BACKFILL_HOURS = 72L
        }
    }
