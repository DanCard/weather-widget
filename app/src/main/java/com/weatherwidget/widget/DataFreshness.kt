package com.weatherwidget.widget

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceQuotaBlocks
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
     * True when any visible source is due under the fetch cadence ([ForecastFetchPolicy], the same
     * rule the sync itself uses) and is not refused until a known time ([SourceQuotaBlocks]).
     *
     * This used rank thresholds (60/90/120 min by list position) that disagreed with the cadence, and
     * counted a quota-blocked source as stale — so on 2026-10-07 every refresh action forced a fetch
     * of all five sources behind Google's 429.
     */
    fun isStaleForSources(
        visibleSources: List<WeatherSource>,
        batchFetchedAtBySource: Map<String, Long>,
        nowMs: Long,
        fetchContext: ForecastFetchContext,
    ): Boolean = visibleSources.any { source ->
        dueState(source, batchFetchedAtBySource[source.id], nowMs, fetchContext) == DueState.DUE
    }

    internal enum class DueState { DUE, FRESH, BLOCKED, SUSPENDED }

    internal fun dueState(
        source: WeatherSource,
        batchFetchedAt: Long?,
        nowMs: Long,
        fetchContext: ForecastFetchContext,
    ): DueState {
        if (SourceQuotaBlocks.isFullyBlocked(source.id, nowMs)) return DueState.BLOCKED
        if (batchFetchedAt == null) return DueState.DUE
        val interval = intervalMinutes(source, fetchContext) ?: return DueState.SUSPENDED
        return if (ForecastFetchPolicy.isDue(batchFetchedAt, interval, nowMs)) DueState.DUE else DueState.FRESH
    }

    private fun intervalMinutes(source: WeatherSource, fetchContext: ForecastFetchContext): Long? =
        ForecastFetchPolicy.intervalMinutes(
            isCharging = fetchContext.isCharging,
            isScreenInteractive = fetchContext.isScreenInteractive,
            isActiveSource = source.id in fetchContext.activeSourceIds,
            batteryLevel = fetchContext.batteryLevel,
        )

    private fun deviceFetchContext(context: Context, stateManager: WidgetStateManager): ForecastFetchContext {
        val snapshot = BatterySnapshotProvider.snapshot(context)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return ForecastFetchContext(
            isCharging = snapshot.isCharging,
            isScreenInteractive = powerManager.isInteractive,
            batteryLevel = snapshot.batteryLevel,
            activeSourceIds = stateManager.getActiveDisplaySourceIds(),
        )
    }

    /**
     * Check if the weather data is stale and needs refreshing.
     *
     * @param context Application context
     * @return true if any visible source is due under [ForecastFetchPolicy] and not quota-blocked
     */
    suspend fun isDataStale(context: Context): Boolean {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val forecastDao = database.forecastDao()
            val stateManager = WidgetStateManager(context)

            val visibleSources = stateManager.getVisibleSourcesOrder()
            if (visibleSources.isEmpty()) {
                Log.d(TAG, "No visible sources found, skipping stale check")
                return false
            }

            val nowMs = System.currentTimeMillis()
            val batchFetchedAtBySource = mutableMapOf<String, Long>()
            for (source in visibleSources) {
                val latestForSource = forecastDao.getLatestWeatherBySource(source.id)
                if (latestForSource == null) {
                    Log.d(TAG, "Source ${source.id} has no data, considering stale")
                    return true
                }
                batchFetchedAtBySource[source.id] = latestForSource.batchFetchedAt
            }

            val result = isStaleForSources(
                visibleSources,
                batchFetchedAtBySource,
                nowMs,
                deviceFetchContext(context, stateManager),
            )
            if (result) {
                Log.d(TAG, "At least one visible source is stale")
            } else {
                Log.d(TAG, "All visible sources are fresh")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking data staleness", e)
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
            val fetchContext = deviceFetchContext(context, stateManager)
            visibleSources.map { source ->
                val latestForSource = forecastDao.getLatestWeatherBySource(source.id)
                if (latestForSource == null) {
                    "${source.id}:missing"
                } else {
                    val ageMinutes = (nowMs - latestForSource.batchFetchedAt) / 60000L
                    val interval = intervalMinutes(source, fetchContext)?.let { "${it}m" } ?: "off"
                    val state = dueState(source, latestForSource.batchFetchedAt, nowMs, fetchContext)
                        .name.lowercase()
                    "${source.id}:${ageMinutes}m/$interval:$state"
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

            // Get location from latest weather data. No cached weather means no location to scope the
            // query to, which is itself the answer: there is no recent hourly data. Substituting a
            // hardcoded coordinate here used to make this look answerable when it was not.
            val latestWeather = weatherDao.getLatestWeather() ?: run {
                Log.d(TAG, "Recent hourly data check: hasData=false (no cached weather, no location)")
                return false
            }
            val lat = latestWeather.locationLat
            val lon = latestWeather.locationLon

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
