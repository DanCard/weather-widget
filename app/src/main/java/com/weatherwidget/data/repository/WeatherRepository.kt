package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.local.WeatherObservationDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val forecastRepository: ForecastRepository,
        private val currentTempRepository: CurrentTempRepository,
        private val weatherDao: WeatherDao,
        private val forecastSnapshotDao: ForecastSnapshotDao,
        private val appLogDao: AppLogDao,
        private val currentTempDao: CurrentTempDao,
    ) {
        suspend fun getWeatherData(
            lat: Double,
            lon: Double,
            locationName: String,
            forceRefresh: Boolean = false,
            networkAllowed: Boolean = true,
        ): Result<List<WeatherEntity>> {
            return forecastRepository.getWeatherData(lat, lon, locationName, forceRefresh, networkAllowed) { source, temp, observedAt, condition ->
                currentTempDao.insert(CurrentTempEntity(java.time.LocalDate.now().toString(), source, lat, lon, temp, observedAt, condition, System.currentTimeMillis()))
            }
        }

        suspend fun refreshCurrentTemperature(
            lat: Double,
            lon: Double,
            locationName: String,
            source: WeatherSource? = null,
            reason: String = "unspecified",
            force: Boolean = false,
        ): Result<Int> {
            return currentTempRepository.refreshCurrentTemperature(lat, lon, locationName, source, reason, force)
        }

        suspend fun getInterpolatedTemperature(lat: Double, lon: Double, time: LocalDateTime = LocalDateTime.now()): Float? = currentTempRepository.getInterpolatedTemperature(lat, lon, time)
        suspend fun getNextInterpolationUpdateTime(lat: Double, lon: Double, time: LocalDateTime = LocalDateTime.now()): LocalDateTime = currentTempRepository.getNextInterpolationUpdateTime(lat, lon, time)
        suspend fun getCachedDataBySource(lat: Double, lon: Double, source: WeatherSource) = forecastRepository.getCachedDataBySource(lat, lon, source)
        suspend fun getForecastForDate(date: String, lat: Double, lon: Double) = forecastRepository.getForecastForDate(date, lat, lon)
        suspend fun getForecastForDateBySource(date: String, lat: Double, lon: Double, source: WeatherSource) = forecastRepository.getForecastForDateBySource(date, lat, lon, source)
        suspend fun getForecastsInRange(startDate: String, endDate: String, lat: Double, lon: Double) = forecastRepository.getForecastsInRange(startDate, endDate, lat, lon)
        suspend fun getWeatherRange(startDate: String, endDate: String, lat: Double, lon: Double) = forecastRepository.getWeatherRange(startDate, endDate, lat, lon)
        suspend fun getLatestLocation(): Pair<Double, Double>? = weatherDao.getLatestWeather()?.let { it.locationLat to it.locationLon }

        val lastNetworkFetchTimeMs: Long get() = FetchMetadata.getLastFullFetchTime(context)
        val lastSuccessfulCheckTimeMs: Long get() = FetchMetadata.getLastSuccessfulCheckTimeMs(context)

        @androidx.annotation.VisibleForTesting
        internal suspend fun saveForecastSnapshot(weather: List<WeatherEntity>, lat: Double, lon: Double, source: String) = forecastRepository.saveForecastSnapshot(weather, lat, lon, source)
        @androidx.annotation.VisibleForTesting
        internal suspend fun fetchFromNws(lat: Double, lon: Double, locationName: String) = forecastRepository.fetchFromNws(lat, lon, locationName)
        @androidx.annotation.VisibleForTesting
        internal suspend fun mergeWithExisting(newData: List<WeatherEntity>, lat: Double, lon: Double) = forecastRepository.mergeWithExisting(newData, lat, lon)
        @androidx.annotation.VisibleForTesting
        internal fun getHistoricalPois() = currentTempRepository.getHistoricalPois()
        @androidx.annotation.VisibleForTesting
        internal fun recordHistoricalPoi(lat: Double, lon: Double, name: String) = currentTempRepository.recordHistoricalPoi(lat, lon, name)
        @androidx.annotation.VisibleForTesting
        internal suspend fun fetchDayObservations(stationsUrl: String, date: java.time.LocalDate) = forecastRepository.fetchDayObservations(stationsUrl, date)
    }
