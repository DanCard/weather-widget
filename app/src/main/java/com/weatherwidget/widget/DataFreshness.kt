package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Utilities for checking data freshness and determining when background fetches are needed.
 */
object DataFreshness {
    private const val TAG = "DataFreshness"

    /**
     * Check if the weather data is stale and needs refreshing.
     *
     * @param context Application context
     * @return true if any visible source is older than its ForecastStalenessPolicy threshold
     */
    suspend fun isDataStale(context: Context): Boolean {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val forecastDao = database.forecastDao()
            val stateManager = WidgetStateManager(context)

            // Get the list of sources currently displayed on active widgets
            val visibleSources = stateManager.getVisibleSourcesOrder()
            if (visibleSources.isEmpty()) {
                Log.d(TAG, "No visible sources found, skipping stale check")
                return false
            }

            val nowMs = System.currentTimeMillis()

            for (source in visibleSources) {
                val latestForSource = forecastDao.getLatestWeatherBySource(source.id)
                if (latestForSource == null) {
                    Log.d(TAG, "Source ${source.id} has no data, considering stale")
                    return true
                }

                // Use batchFetchedAt to represent the age of the forecast set
                val ageMs = nowMs - latestForSource.batchFetchedAt
                val position = visibleSources.indexOf(source)
                val thresholdMs = ForecastStalenessPolicy.getStalenessThresholdMs(position)

                val isSourceStale = ageMs > thresholdMs
                if (isSourceStale) {
                    Log.d(
                        TAG,
                        "Source ${source.id} is stale (age=${ageMs / 60000}m, threshold=${thresholdMs / 60000}m)",
                    )
                    return true
                }
            }

            Log.d(TAG, "All visible sources are fresh")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking data staleness", e)
            // On error, assume data is stale to be safe
            true
        }
    }

    /**
     * Build a compact summary of visible-source ages and thresholds for persisted diagnostics.
     */
    suspend fun getVisibleSourceFreshnessSummary(context: Context): String {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val forecastDao = database.forecastDao()
            val stateManager = WidgetStateManager(context)
            val visibleSources = stateManager.getVisibleSourcesOrder()
            if (visibleSources.isEmpty()) {
                return "visibleSources=none"
            }

            val nowMs = System.currentTimeMillis()
            visibleSources.mapIndexed { index, source ->
                val latestForSource = forecastDao.getLatestWeatherBySource(source.id)
                if (latestForSource == null) {
                    "${source.id}:missing"
                } else {
                    val ageMinutes = (nowMs - latestForSource.batchFetchedAt) / 60000L
                    val thresholdMinutes =
                        ForecastStalenessPolicy.getStalenessThresholdMs(index) / 60000L
                    val state = if (ageMinutes > thresholdMinutes) "stale" else "fresh"
                    "${source.id}:${ageMinutes}m/${thresholdMinutes}m:$state"
                }
            }.joinToString(
                prefix = "visibleSources=",
                separator = ",",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building freshness summary", e)
            "visibleSources=error:${e.javaClass.simpleName}"
        }
    }

    /**
     * Get the age of the most stale visible weather data in minutes.
     *
     * @param context Application context
     * @return Age in minutes of the oldest visible source, or null if no data available
     */
    suspend fun getDataAgeMinutes(context: Context): Long? {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val forecastDao = database.forecastDao()
            val stateManager = WidgetStateManager(context)

            val visibleSources = stateManager.getVisibleSourcesOrder()
            if (visibleSources.isEmpty()) return null

            var maxAgeMs: Long? = null
            val nowMs = System.currentTimeMillis()

            for (source in visibleSources) {
                val latestForSource = forecastDao.getLatestWeatherBySource(source.id)
                if (latestForSource != null) {
                    val ageMs = nowMs - latestForSource.batchFetchedAt
                    maxAgeMs = if (maxAgeMs == null) ageMs else Math.max(maxAgeMs, ageMs)
                }
            }

            maxAgeMs?.div(60000L)
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
            val zoneId = ZoneId.systemDefault()
            val startTimeMs = now.minusHours(1).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val endTimeMs = now.plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()

            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTimeMs, endTimeMs, lat, lon)
            val hasData = hourlyForecasts.isNotEmpty()

            Log.d(TAG, "Recent hourly data check: hasData=$hasData (${hourlyForecasts.size} entries)")
            hasData
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hourly data", e)
            false
        }
    }
}
