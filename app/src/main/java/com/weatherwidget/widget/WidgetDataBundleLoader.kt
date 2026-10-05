package com.weatherwidget.widget

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.data.local.ObservationEntity
import java.time.LocalDate
import java.time.ZoneId

internal data class WidgetDataBundle(
    val weatherList: List<ForecastEntity>,
    val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
    val hourlyForecasts: List<HourlyForecastEntity>,
    val dailyActuals: DailyActualsBySource,
    val currentTemps: List<ObservationEntity>,
    val activeSourceIds: List<String>,
)

internal class WidgetDataBundleLoader(
    private val weatherRepository: WeatherRepository,
    private val hourlyForecastLoader: HourlyForecastLoader,
    private val context: android.content.Context,
) {
    suspend fun load(
        latitude: Double,
        longitude: Double,
        networkAllowed: Boolean,
        recomputeActuals: Boolean,
        forceRefresh: Boolean,
        targetSourceId: String?,
        fetchContext: ForecastFetchContext?,
        /** Which path asked, for the BUNDLE_PERF line. */
        caller: String = "unspecified",
        /**
         * Hourly rows the caller already loaded for this site under the same source scope
         * ([HourlyForecastLoader.hourlySourceIds]); skips the second load of the same rows.
         */
        preloadedHourly: List<HourlyForecastEntity>? = null,
    ): WidgetDataBundle {
        val startMs = android.os.SystemClock.elapsedRealtime()
        val weatherList = weatherRepository.getWeatherData(
            latitude = latitude,
            longitude = longitude,
            forceRefresh = forceRefresh,
            networkAllowed = networkAllowed,
            targetSourceId = targetSourceId,
            fetchContext = fetchContext,
        ).getOrDefault(emptyList())

        val afterWeatherMs = android.os.SystemClock.elapsedRealtime()
        val forecastSnapshots = fetchForecastSnapshots(latitude, longitude)
        val afterSnapshotsMs = android.os.SystemClock.elapsedRealtime()
        val hourlyForecasts = preloadedHourly ?: hourlyForecastLoader.load(
            lat = latitude,
            lon = longitude,
            sources = hourlyForecastLoader.hourlySourceIds(),
            caller = "bundle",
        )
        val activeSourceIds = hourlyForecastLoader.currentDisplaySourceIds()
        val afterHourlyMs = android.os.SystemClock.elapsedRealtime()

        val dailyActuals = fetchDailyActuals(
            lat = latitude,
            lon = longitude,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = activeSourceIds,
            recompute = recomputeActuals,
        )
        val afterActualsMs = android.os.SystemClock.elapsedRealtime()
        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
            latitude,
            longitude,
            todayStartMs,
        )
        val endMs = android.os.SystemClock.elapsedRealtime()
        // Kept permanently (keep-diagnostics rule): a cold-process cache repaint spent ~9 s here with
        // no log line to attribute it to (performance/260929-cold-process-location-change-repaint.md).
        runCatching {
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "BUNDLE_PERF",
                "caller=$caller total=${endMs - startMs}ms weather=${afterWeatherMs - startMs}ms " +
                    "snapshots=${afterSnapshotsMs - afterWeatherMs}ms hourly=${afterHourlyMs - afterSnapshotsMs}ms " +
                    "actuals=${afterActualsMs - afterHourlyMs}ms currentTemps=${endMs - afterActualsMs}ms " +
                    "recompute=$recomputeActuals hourlyRows=${hourlyForecasts.size} preloaded=${preloadedHourly != null} " +
                    "processAgeMs=${com.weatherwidget.WeatherWidgetApp.processAgeMs()}",
                "INFO",
            )
        }

        return WidgetDataBundle(
            weatherList = weatherList,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            dailyActuals = dailyActuals,
            currentTemps = currentTemps,
            activeSourceIds = activeSourceIds,
        )
    }

    internal suspend fun fetchForecastSnapshots(
        lat: Double,
        lon: Double,
    ): Map<LocalDate, List<ForecastEntity>> {
        return try {
            val today = LocalDate.now()
            val pastStart = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val pastEnd = today.minusDays(2).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val recentStart = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val recentEnd =
                today.plusDays(
                    com.weatherwidget.widget.handlers.DailyLoadWindowResolver
                        .resolve(context).forecastDays,
                ).toEpochDay() * WidgetConstants.MS_IN_A_DAY

            val pastSnapshots = weatherRepository.getLatestForecastsInRange(pastStart, pastEnd, lat, lon)
            val recentSnapshots = weatherRepository.getAllForecastsInRange(recentStart, recentEnd, lat, lon)
            // Appended after the newest rows, so each day's first row is still its newest.
            val priorCandidates = WeatherDatabase.getDatabase(context).forecastDao()
                .getPriorForecastCandidates(pastStart, pastEnd, lat, lon)
            val grouped = (pastSnapshots + recentSnapshots + priorCandidates).distinct()
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }

            val gapFiller = ClimateGapFiller(WeatherDatabase.getDatabase(context).climateNormalDao())
            gapFiller.appendGapsToSnapshots(
                grouped,
                lat,
                lon,
                today,
                horizonDays = WidgetQueryWindows.DAILY_FORECAST_DAYS,
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch forecast snapshots", e)
            emptyMap()
        }
    }

    internal suspend fun fetchDailyActuals(
        lat: Double,
        lon: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
        recompute: Boolean = true,
    ): DailyActualsBySource {
        return try {
            if (recompute) {
                val start = LocalDate.now().minusDays(2)
                val yesterday = LocalDate.now().minusDays(1)
                weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, start, yesterday, hourlyForecasts)
            }
            weatherRepository.getDailyActualsWithLiveToday(lat, lon, hourlyForecasts, activeSourceList)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch daily actuals", e)
            emptyMap()
        }
    }

    suspend fun reloadDailyActuals(
        lat: Double,
        lon: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        sourceIds: List<String>,
    ): DailyActualsBySource {
        return fetchDailyActuals(
            lat = lat,
            lon = lon,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = sourceIds,
            recompute = false,
        )
    }

    private companion object {
        private const val TAG = "WidgetDataBundleLoader"
    }
}
