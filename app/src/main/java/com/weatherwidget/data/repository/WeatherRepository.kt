package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
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
        private val forecastDao: ForecastDao,
        private val appLogDao: AppLogDao,
        private val currentTempDao: CurrentTempDao,
    ) {
        suspend fun getWeatherData(
            latitude: Double,
            longitude: Double,
            locationName: String,
            forceRefresh: Boolean = false,
            networkAllowed: Boolean = true,
            targetSourceId: String? = null,
        ): Result<List<ForecastEntity>> {
            return forecastRepository.getWeatherData(
                latitude, longitude, locationName, forceRefresh, networkAllowed, targetSourceId
            ) { source, temperature, observedAt, condition ->
                currentTempDao.insert(
                    CurrentTempEntity(
                        java.time.LocalDate.now().toString(), 
                        source, 
                        latitude, 
                        longitude, 
                        temperature, 
                        observedAt, 
                        condition, 
                        System.currentTimeMillis()
                    )
                )
            }
        }

        suspend fun refreshCurrentTemperature(
            latitude: Double,
            longitude: Double,
            locationName: String,
            source: WeatherSource? = null,
            reason: String = "unspecified",
            forceRefresh: Boolean = false,
        ): Result<Int> {
            return currentTempRepository.refreshCurrentTemperature(latitude, longitude, locationName, source, reason, forceRefresh)
        }

        suspend fun getInterpolatedTemperature(
            latitude: Double, 
            longitude: Double, 
            time: LocalDateTime = LocalDateTime.now()
        ): Float? = currentTempRepository.getInterpolatedTemperature(latitude, longitude, time)
        
        suspend fun getNextInterpolationUpdateTime(
            latitude: Double, 
            longitude: Double, 
            time: LocalDateTime = LocalDateTime.now()
        ): LocalDateTime = currentTempRepository.getNextInterpolationUpdateTime(latitude, longitude, time)
        
        suspend fun getCachedDataBySource(latitude: Double, longitude: Double, source: WeatherSource) = 
            forecastRepository.getCachedDataBySource(latitude, longitude, source)
            
        suspend fun getForecastForDate(dateString: String, latitude: Double, longitude: Double) = 
            forecastRepository.getForecastForDate(dateString, latitude, longitude)
            
        suspend fun getForecastForDateBySource(dateString: String, latitude: Double, longitude: Double, source: WeatherSource) = 
            forecastRepository.getForecastForDateBySource(dateString, latitude, longitude, source)
            
        suspend fun getForecastsInRange(startDate: String, endDate: String, latitude: Double, longitude: Double) = 
            forecastRepository.getForecastsInRange(startDate, endDate, latitude, longitude)
            
        suspend fun getWeatherRange(startDate: String, endDate: String, latitude: Double, longitude: Double) = 
            forecastRepository.getWeatherRange(startDate, endDate, latitude, longitude)
            
        suspend fun getLatestLocation(): Pair<Double, Double>? = 
            forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }

        val lastNetworkFetchTimeMs: Long get() = FetchMetadata.getLastFullFetchTime(context)
        val lastSuccessfulCheckTimeMs: Long get() = FetchMetadata.getLastSuccessfulCheckTimeMs(context)

        suspend fun getHistoricalNormalsByMonthDay(latitude: Double, longitude: Double) = 
            forecastRepository.getHistoricalNormalsByMonthDay(latitude, longitude)

        @androidx.annotation.VisibleForTesting
        internal suspend fun saveForecastSnapshot(
            weatherForecasts: List<ForecastEntity>, 
            latitude: Double, 
            longitude: Double, 
            sourceId: String
        ) = forecastRepository.saveForecastSnapshot(weatherForecasts, latitude, longitude, sourceId)
        
        @androidx.annotation.VisibleForTesting
        internal suspend fun fetchFromNws(latitude: Double, longitude: Double, locationName: String) = 
            forecastRepository.fetchFromNws(latitude, longitude, locationName)
            
        @androidx.annotation.VisibleForTesting
        internal fun getHistoricalPois() = currentTempRepository.getHistoricalPois()
        
        @androidx.annotation.VisibleForTesting
        internal fun recordHistoricalPoi(latitude: Double, longitude: Double, name: String) = 
            currentTempRepository.recordHistoricalPoi(latitude, longitude, name)
            
        @androidx.annotation.VisibleForTesting
        internal suspend fun fetchDayObservations(stationsUrl: String, date: java.time.LocalDate) = 
            forecastRepository.fetchDayObservations(stationsUrl, date)

        suspend fun backfillNwsObservationsIfNeeded(latitude: Double, longitude: Double) = 
            currentTempRepository.backfillNwsObservationsIfNeeded(latitude, longitude)
    }
