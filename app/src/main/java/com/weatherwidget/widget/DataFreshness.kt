package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Utilities for checking data freshness and determining when background fetches are needed.
 */
object DataFreshness {
    private const val TAG = "DataFreshness"
    private const val STALENESS_THRESHOLD_MINUTES = 30L

    /**
     * Check if the weather data is stale and needs refreshing.
     *
     * @param context Application context
     * @return true if data is older than STALENESS_THRESHOLD_MINUTES, false otherwise
     */
    suspend fun isDataStale(context: Context): Boolean {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val forecastDao = database.forecastDao()

            val latestWeather = forecastDao.getLatestWeather()
            if (latestWeather == null) {
                Log.d(TAG, "No weather data available, considering stale")
                return true
            }

            val fetchedAt =
                java.time.Instant.ofEpochMilli(latestWeather.fetchedAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
            val now = LocalDateTime.now()
            val minutesSinceFetch = ChronoUnit.MINUTES.between(fetchedAt, now)

            val isStale = minutesSinceFetch > STALENESS_THRESHOLD_MINUTES
            Log.d(
                TAG,
                "Data fetched $minutesSinceFetch minutes ago, stale=$isStale " +
                    "(threshold=$STALENESS_THRESHOLD_MINUTES min)",
            )

            isStale
        } catch (e: Exception) {
            Log.e(TAG, "Error checking data staleness", e)
            // On error, assume data is stale to be safe
            true
        }
    }

    /**
     * Get the age of the most recent weather data in minutes.
     *
     * @param context Application context
     * @return Age in minutes, or null if no data available
     */
    suspend fun getDataAgeMinutes(context: Context): Long? {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val weatherDao = database.forecastDao()

            val latestWeather = weatherDao.getLatestWeather()
            if (latestWeather == null) {
                return null
            }

            val fetchedAt =
                java.time.Instant.ofEpochMilli(latestWeather.fetchedAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
            val now = LocalDateTime.now()
            ChronoUnit.MINUTES.between(fetchedAt, now)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting data age", e)
            null
        }
    }

    /**
     * Check if hourly forecast data is available for current temperature interpolation.
     *
     * @param context Application context
     * @return true if hourly data exists around current time
     */
    suspend fun hasRecentHourlyData(context: Context): Boolean {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val hourlyDao = database.hourlyForecastDao()
            val weatherDao = database.forecastDao()

            // Get location from latest weather data
            val latestWeather = weatherDao.getLatestWeather()
            val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

            val now = LocalDateTime.now()
            val startTime = now.minusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)
            val hasData = hourlyForecasts.isNotEmpty()

            Log.d(TAG, "Recent hourly data check: hasData=$hasData (${hourlyForecasts.size} entries)")
            hasData
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hourly data", e)
            false
        }
    }
}
