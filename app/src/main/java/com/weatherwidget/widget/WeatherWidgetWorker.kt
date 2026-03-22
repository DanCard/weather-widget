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
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val isPlugged = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) > 0
            val isScreenInteractive = isScreenInteractive()

            val lastFullFetchMs = weatherRepository.lastNetworkFetchTimeMs
            val lastFullFetchAge = if (lastFullFetchMs > 0) (System.currentTimeMillis() - lastFullFetchMs) / 1000 else -1
            appLogDao.log(
                "SYNC_START",
                "uiOnly=$uiOnlyRefresh, force=$forceRefresh, currentOnly=$currentTempOnly, " +
                    "opportunistic=$opportunisticCurrentTemp, battery=$batteryLevel%, plugged=$isPlugged, " +
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

            // Reset toggle states only on scheduled refreshes (not UI-only or forced refreshes)
            // Forced refreshes are triggered by user toggle actions, so preserve the user's choice
            if (!uiOnlyRefresh && !forceRefresh) {
                widgetStateManager.resetAllToggleStates()
            }

            return try {
                val startMs = SystemClock.elapsedRealtime()
                val location =
                    weatherRepository.getLatestLocation()
                        ?: (DEFAULT_LAT to DEFAULT_LON)
                Log.d(TAG, "doWork: Location = $location")

                val result =
                    weatherRepository.getWeatherData(
                        latitude = location.first,
                        longitude = location.second,
                        locationName = getLocationName(location.first, location.second),
                        forceRefresh = forceRefresh && !uiOnlyRefresh,
                        networkAllowed = WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh),
                        targetSourceId = targetSourceId
                    )

                result.fold(
                    onSuccess = { weatherList ->
                        val afterWeatherMs = SystemClock.elapsedRealtime()
                        Log.d(TAG, "doWork: Got ${weatherList.size} weather entries")

                        // Fetch forecast snapshots for comparison
                        val forecastSnapshots = fetchForecastSnapshots(location.first, location.second)
                        // Fetch hourly forecasts for interpolation
                        val hourlyForecasts = fetchHourlyForecasts(location.first, location.second)
                        val afterHourlyMs = SystemClock.elapsedRealtime()

                        // Backfill NWS history if this is a new location or no history exists
                        // ONLY perform if not a UI-only refresh to avoid blocking during frequent updates
                        if (!uiOnlyRefresh && (targetSourceId == com.weatherwidget.data.model.WeatherSource.NWS.id || (targetSourceId == null && weatherList.any { it.source == com.weatherwidget.data.model.WeatherSource.NWS.id }))) {
                            Log.d(TAG, "doWork: Triggering NWS backfill check")
                            weatherRepository.backfillNwsObservationsIfNeeded(location.first, location.second)
                        }
                        val afterBackfillMs = SystemClock.elapsedRealtime()

                        val todayStartMs = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
                            location.first,
                            location.second,
                            todayStartMs,
                        )

                        appLogDao.log("SYNC_SUCCESS", "Weather=${weatherList.size}, Snapshots=${forecastSnapshots.size}, Hourly=${hourlyForecasts.size}", "INFO")

                        val dailyActuals = fetchDailyActuals(location.first, location.second, recompute = !uiOnlyRefresh)
                        val afterActualsMs = SystemClock.elapsedRealtime()

                        appLogDao.log("WIDGET_LIFECYCLE", "phase=worker_paint_start uiOnly=$uiOnlyRefresh thread=${Thread.currentThread().name}")
                        updateAllWidgets(weatherList, forecastSnapshots, hourlyForecasts, currentTemps, dailyActuals)
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
                            // Schedule next UI update after data fetch
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
            } catch (e: Exception) {
                appLogDao.log("SYNC_EXCEPTION", "${e.javaClass.simpleName}: ${e.message}", "ERROR")
                Result.retry()
            }
        }

        private suspend fun fetchForecastSnapshots(
            lat: Double,
            lon: Double,
        ): Map<String, List<ForecastEntity>> {
            return try {
                val startDate = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endDate = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val snapshots = weatherRepository.getAllForecastsInRange(startDate, endDate, lat, lon)
                snapshots.groupBy { it.targetDate }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch forecast snapshots", e)
                emptyMap()
            }
        }

        private suspend fun fetchDailyActuals(
            lat: Double,
            lon: Double,
            recompute: Boolean = true,
        ): DailyActualsBySource {
            return try {
                val startLocalDate = LocalDate.now().minusDays(30)
                val endLocalDate = LocalDate.now().plusDays(1)
                
                if (recompute) {
                    weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, startLocalDate, endLocalDate)
                }
                
                val startDate = startLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endDate = endLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val extremes = WeatherDatabase.getDatabase(context).dailyExtremeDao()
                    .getExtremesInRange(startDate, endDate, lat, lon)
                ObservationResolver.extremesToDailyActualsBySource(extremes)
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
                val now = LocalDateTime.now()
                val zoneId = ZoneId.systemDefault()
                // Extended range for hourly view and rain analysis: 24h past to 96h future (today + 4 days)
                val startTimeMs = now.minusHours(24).atZone(zoneId).toInstant().toEpochMilli()
                val endTimeMs = now.plusHours(96).atZone(zoneId).toInstant().toEpochMilli()
                hourlyDao.getHourlyForecasts(startTimeMs, endTimeMs, lat, lon)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch hourly forecasts", e)
                emptyList()
            }
        }

        private suspend fun updateAllWidgets(
            weatherList: List<ForecastEntity>,
            forecastSnapshots: Map<String, List<ForecastEntity>>,
            hourlyForecasts: List<HourlyForecastEntity>,
            currentTemps: List<com.weatherwidget.data.local.ObservationEntity> = emptyList(),
            dailyActuals: DailyActualsBySource = emptyMap(),
        ) = coroutineScope {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                val job = launch {
                    WeatherWidgetProvider.updateWidgetWithData(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        weatherList = weatherList,
                        forecastSnapshots = forecastSnapshots,
                        hourlyForecasts = hourlyForecasts,
                        currentTemps = currentTemps,
                        dailyActualsBySource = dailyActuals,
                        repository = weatherRepository
                    )
                }
                WidgetUpdateTracker.trackJob(appWidgetId, job)
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
            return try {
                val isManual = reason.contains("manual") || reason.contains("force") || force
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
                } else {
                    val location = weatherRepository.getLatestLocation() ?: (DEFAULT_LAT to DEFAULT_LON)
                    val refreshResult =
                        weatherRepository.refreshCurrentTemperature(
                            latitude = location.first,
                            longitude = location.second,
                            locationName = getLocationName(location.first, location.second),
                            source = targetSource,
                            reason = reason,
                            forceRefresh = force,
                        )

                    refreshResult.fold(
                        onSuccess = { updated ->
                            appLogDao.log("CURR_FETCH_DONE", "reason=$reason updated=$updated")
                        },
                        onFailure = { e ->
                            appLogDao.log("CURR_FETCH_FAIL", "reason=$reason ${e.message}", "ERROR")
                        },
                    )
                }

                refreshWidgetsFromCache()
                manageCurrentTempLoopAfterRun(isPlugged, isScreenInteractive)
                Result.success()
            } catch (e: Exception) {
                appLogDao.log("CURR_FETCH_EXCEPTION", "reason=$reason ${e.javaClass.simpleName}: ${e.message}", "ERROR")
                manageCurrentTempLoopAfterRun(isPlugged, isScreenInteractive)
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
            } catch (e: Exception) {
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_EXCEPTION",
                    "reason=$reason ${e.javaClass.simpleName}: ${e.message}",
                    "ERROR",
                )
                Result.retry()
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
            val dailyActuals = fetchDailyActuals(location.first, location.second, recompute = false)
            val hourlyForecasts = fetchHourlyForecasts(location.first, location.second)
            val todayStartMs2 = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
                location.first,
                location.second,
                todayStartMs2,
            )
            updateAllWidgets(weatherList, forecastSnapshots, hourlyForecasts, currentTemps, dailyActuals)
        }

        private fun manageCurrentTempLoopAfterRun(
            isPlugged: Boolean,
            isScreenInteractive: Boolean,
        ) {
            if (CurrentTempFetchPolicy.shouldScheduleChargingLoop(isPlugged, isScreenInteractive)) {
                CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context)
            } else {
                CurrentTempUpdateScheduler.cancel(context)
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
            const val DEFAULT_OBSERVATION_BACKFILL_HOURS = 12L
        }
    }
