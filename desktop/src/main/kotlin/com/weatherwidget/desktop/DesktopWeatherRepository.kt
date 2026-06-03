package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopWeatherRepository(
    private val weatherService: DesktopWeatherService,
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val weatherSource: String
) {
    suspend fun loadCached(): ForecastResult? = withContext(Dispatchers.IO) {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours for cache
        val hourly = weatherDao.getLatestHourly(latitude, longitude, weatherSource, maxAgeMs)
        val daily = weatherDao.getDailyForecasts(latitude, longitude, weatherSource)
        val latestObs = weatherDao.getLatestObservation(latitude, longitude, maxAgeMs)

        if (hourly.isEmpty() && daily.isEmpty()) {
            return@withContext null
        }

        ForecastResult(
            currentTemp = latestObs?.temperature ?: hourly.firstOrNull()?.temperature,
            currentCondition = latestObs?.condition ?: hourly.firstOrNull()?.condition,
            currentObservedAt = latestObs?.timestamp ?: hourly.firstOrNull()?.dateTime,
            daily = daily,
            hourly = hourly
        )
    }

    suspend fun refresh(): ForecastResult = withContext(Dispatchers.IO) {
        val result = weatherService.fetchForecast()
        
        // Persist
        weatherDao.upsertHourlyForecasts(latitude, longitude, weatherSource, result.hourly)
        weatherDao.upsertForecasts(latitude, longitude, weatherSource, result.daily)
        
        if (result.rawObservations.isNotEmpty()) {
            weatherDao.upsertObservations(result.rawObservations)
        }
        
        // Snapshot for history (Tier 1 simplification: 4h buckets)
        val snapshotBucket = (System.currentTimeMillis() / (4 * 3600 * 1000L)) * (4 * 3600 * 1000L)
        weatherDao.upsertHourlyForecastHistory(latitude, longitude, weatherSource, snapshotBucket, result.hourly)
        
        // Cleanup old data (> 30 days)
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 3600 * 1000)
        weatherDao.cleanup(thirtyDaysAgo)
        
        result
    }
}
