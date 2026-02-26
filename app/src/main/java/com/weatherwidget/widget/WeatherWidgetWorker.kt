package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.WeatherRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalDateTime
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
            val uiOnlyRefresh = inputData.getBoolean(KEY_UI_ONLY_REFRESH, false)
            val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
            val currentTempOnly = inputData.getBoolean(KEY_CURRENT_TEMP_ONLY, false)
            val opportunisticCurrentTemp = inputData.getBoolean(KEY_CURRENT_TEMP_OPPORTUNISTIC, false)
            val currentTempReason = inputData.getString(KEY_CURRENT_TEMP_REASON) ?: "unspecified"

            val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val isPlugged = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) > 0
            val isScreenInteractive = isScreenInteractive()

            val lastFetchAge = (System.currentTimeMillis() - weatherRepository.lastNetworkFetchTimeMs) / 1000
            appLogDao.log(
                "SYNC_START",
                "uiOnly=$uiOnlyRefresh, force=$forceRefresh, currentOnly=$currentTempOnly, " +
                    "opportunistic=$opportunisticCurrentTemp, battery=$batteryLevel%, plugged=$isPlugged, " +
                    "interactive=$isScreenInteractive, reason=$currentTempReason, lastFetch=${lastFetchAge}s ago",
            )

            if (currentTempOnly) {
                return handleCurrentTempOnlyWork(
                    isPlugged = isPlugged,
                    isScreenInteractive = isScreenInteractive,
                    isOpportunisticContext = opportunisticCurrentTemp,
                    reason = currentTempReason,
                )
            }

            // Reset toggle states only on scheduled refreshes (not UI-only or forced refreshes)
            // Forced refreshes are triggered by user toggle actions, so preserve the user's choice
            if (!uiOnlyRefresh && !forceRefresh) {
                widgetStateManager.resetAllToggleStates()
            }

            return try {
                val location =
                    weatherRepository.getLatestLocation()
                        ?: (DEFAULT_LAT to DEFAULT_LON)
                Log.d(TAG, "doWork: Location = $location")

                val result =
                    weatherRepository.getWeatherData(
                        lat = location.first,
                        lon = location.second,
                        locationName = getLocationName(location.first, location.second),
                        forceRefresh = forceRefresh && !uiOnlyRefresh,
                        networkAllowed = WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh),
                    )

                result.fold(
                    onSuccess = { weatherList ->
                        Log.d(TAG, "doWork: Got ${weatherList.size} weather entries")

                        // Fetch forecast snapshots for comparison
                        val forecastSnapshots = fetchForecastSnapshots(location.first, location.second)
                        // Fetch hourly forecasts for interpolation
                        val hourlyForecasts = fetchHourlyForecasts(location.first, location.second)

                        appLogDao.log("SYNC_SUCCESS", "Weather=${weatherList.size}, Snapshots=${forecastSnapshots.size}, Hourly=${hourlyForecasts.size}")

                        updateAllWidgets(weatherList, forecastSnapshots, hourlyForecasts)
                        if (!uiOnlyRefresh) {
                            scheduleNextUpdate()
                            // Schedule next UI update after data fetch
                            val uiScheduler = UIUpdateScheduler(context)
                            uiScheduler.scheduleNextUpdate()
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
        ): Map<String, List<ForecastSnapshotEntity>> {
            return try {
                val startDate = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val snapshots = weatherRepository.getForecastsInRange(startDate, endDate, lat, lon)
                // Group by target date, keeping all sources for each date
                // For each date, we want the most recent forecast from each API source
                snapshots.groupBy { it.targetDate }
                    .mapValues { (_, forecasts) ->
                        // Group by source and take most recent for each
                        forecasts.groupBy { it.source }
                            .mapValues { (_, sourceForecasts) -> sourceForecasts.maxByOrNull { it.forecastDate }!! }
                            .values.toList()
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch forecast snapshots", e)
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
                // Extended range for hourly view and rain analysis: 24h past to 96h future (today + 4 days)
                val startTime = now.minusHours(24).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val endTime = now.plusHours(96).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch hourly forecasts", e)
                emptyList()
            }
        }

        private fun updateAllWidgets(
            weatherList: List<com.weatherwidget.data.local.WeatherEntity>,
            forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
            hourlyForecasts: List<HourlyForecastEntity>,
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                WeatherWidgetProvider.updateWidgetWithData(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    weatherList = weatherList,
                    forecastSnapshots = forecastSnapshots,
                    hourlyForecasts = hourlyForecasts,
                )
            }
        }

        private fun scheduleNextUpdate() {
            val intervalMinutes = getUpdateIntervalMinutes() ?: run {
                Log.d(TAG, "BATTERY_SAVE: Below 50%, skipping scheduled update")
                return
            }

            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "weather_widget_next_update",
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }

        private suspend fun handleCurrentTempOnlyWork(
            isPlugged: Boolean,
            isScreenInteractive: Boolean,
            isOpportunisticContext: Boolean,
            reason: String,
        ): Result {
            return try {
                if (
                    !CurrentTempFetchPolicy.shouldFetchNow(
                        isCharging = isPlugged,
                        isScreenInteractive = isScreenInteractive,
                        isOpportunisticContext = isOpportunisticContext,
                    )
                ) {
                    appLogDao.log(
                        "CURR_FETCH_SKIP",
                        "reason=$reason policy_blocked charging=$isPlugged interactive=$isScreenInteractive opportunistic=$isOpportunisticContext",
                        "WARN",
                    )
                } else {
                    val location = weatherRepository.getLatestLocation() ?: (DEFAULT_LAT to DEFAULT_LON)
                    val refreshResult =
                        weatherRepository.refreshCurrentTemperature(
                            lat = location.first,
                            lon = location.second,
                            locationName = getLocationName(location.first, location.second),
                            reason = reason,
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

        private suspend fun refreshWidgetsFromCache() {
            val location = weatherRepository.getLatestLocation() ?: (DEFAULT_LAT to DEFAULT_LON)
            val weatherList =
                weatherRepository.getWeatherData(
                    lat = location.first,
                    lon = location.second,
                    locationName = getLocationName(location.first, location.second),
                    networkAllowed = false,
                ).getOrDefault(emptyList())
            val forecastSnapshots = fetchForecastSnapshots(location.first, location.second)
            val hourlyForecasts = fetchHourlyForecasts(location.first, location.second)
            updateAllWidgets(weatherList, forecastSnapshots, hourlyForecasts)
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

        private fun getUpdateIntervalMinutes(): Long? {
            val batteryStatus: Intent? =
                IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                    context.registerReceiver(null, filter)
                }

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val level =
                batteryStatus?.let { intent ->
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    level * 100 / scale
                } ?: 100

            return BatteryFetchStrategy.computeFetchInterval(isCharging, level)
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
        }
    }
