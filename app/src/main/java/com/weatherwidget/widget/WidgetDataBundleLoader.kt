package com.weatherwidget.widget

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
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
    ): WidgetDataBundle {
        val weatherList = weatherRepository.getWeatherData(
            latitude = latitude,
            longitude = longitude,
            forceRefresh = forceRefresh,
            networkAllowed = networkAllowed,
            targetSourceId = targetSourceId,
            fetchContext = fetchContext,
        ).getOrDefault(emptyList())

        val forecastSnapshots = fetchForecastSnapshots(latitude, longitude)
        val hourlyForecasts = hourlyForecastLoader.load(
            lat = latitude,
            lon = longitude,
            sources = hourlyForecastLoader.hourlySourceIds(),
        )
        val activeSourceIds = hourlyForecastLoader.currentDisplaySourceIds()

        val dailyActuals = fetchDailyActuals(
            lat = latitude,
            lon = longitude,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = activeSourceIds,
            recompute = recomputeActuals,
        )
        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTemps = weatherRepository.getMainObservationsWithComputedNwsBlend(
            latitude,
            longitude,
            todayStartMs,
        )

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
            val grouped = (pastSnapshots + recentSnapshots).groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }

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
