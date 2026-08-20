package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.LocationMatch

/**
 * Global store for fetch timestamps to avoid requiring a Repository instance
 * in simple contexts like WidgetProviders.
 */
object FetchMetadata {
    private const val PREFS_NAME = "weather_fetch_metadata"
    private const val KEY_LAST_FULL_FETCH = "last_full_fetch_time"
    private const val KEY_LAST_CURRENT_TEMP_FETCH = "last_current_temp_fetch_time"
    private const val KEY_LAST_FORECAST_SOURCE_SUCCESS_PREFIX = "last_forecast_source_success"

    private fun getPrefs(context: Context): SharedPreferences {
        return com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
    }

    fun getLastFullFetchTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_FULL_FETCH, 0L)
    }

    fun setLastFullFetchTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_FULL_FETCH, time).apply()
    }

    fun getLastCurrentTempFetchTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_CURRENT_TEMP_FETCH, 0L)
    }

    fun setLastCurrentTempFetchTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_CURRENT_TEMP_FETCH, time).apply()
    }

    /**
     * Last current-temperature fetch for one physical site, quantized the same way the forecast
     * source success key is. The global [getLastCurrentTempFetchTime] is deliberately NOT used as
     * the freshness gate: it is location-agnostic, so a location handoff ~800 m away would inherit
     * the previous site's 5-minute cooldown and skip the refetch the new site needs.
     */
    fun getLastCurrentTempFetchTime(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): Long = getPrefs(context).getLong(currentTempFetchKey(latitude, longitude), 0L)

    fun setLastCurrentTempFetchTime(
        context: Context,
        latitude: Double,
        longitude: Double,
        time: Long,
    ) {
        getPrefs(context).edit()
            .putLong(currentTempFetchKey(latitude, longitude), time)
            .apply()
        // Keep the legacy global marker current for [getLastSuccessfulCheckTimeMs].
        setLastCurrentTempFetchTime(context, time)
    }

    private fun currentTempFetchKey(latitude: Double, longitude: Double): String {
        val lat = LocationMatch.quantize(latitude)
        val lon = LocationMatch.quantize(longitude)
        return "${KEY_LAST_CURRENT_TEMP_FETCH}_${lat}_$lon"
    }

    /**
     * Last non-empty successful forecast response for one provider at one physical site.
     *
     * This is deliberately separate from forecast-row `fetchedAt`: unchanged responses do not rewrite
     * rows, so row timestamps describe content age rather than the age of the latest successful check.
     */
    fun getLastForecastSourceSuccessTime(
        context: Context,
        sourceId: String,
        latitude: Double,
        longitude: Double,
    ): Long = getPrefs(context).getLong(forecastSourceSuccessKey(sourceId, latitude, longitude), 0L)

    fun setLastForecastSourceSuccessTime(
        context: Context,
        sourceId: String,
        latitude: Double,
        longitude: Double,
        time: Long,
    ) {
        getPrefs(context).edit()
            .putLong(forecastSourceSuccessKey(sourceId, latitude, longitude), time)
            .apply()
    }

    private fun forecastSourceSuccessKey(
        sourceId: String,
        latitude: Double,
        longitude: Double,
    ): String {
        val lat = LocationMatch.quantize(latitude)
        val lon = LocationMatch.quantize(longitude)
        return "${KEY_LAST_FORECAST_SOURCE_SUCCESS_PREFIX}_${sourceId}_${lat}_$lon"
    }

    /**
     * The most recent successful API check of any kind.
     */
    fun getLastSuccessfulCheckTimeMs(context: Context): Long {
        return maxOf(getLastFullFetchTime(context), getLastCurrentTempFetchTime(context))
    }
}
