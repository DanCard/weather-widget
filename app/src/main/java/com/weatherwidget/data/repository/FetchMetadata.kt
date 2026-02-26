package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Global store for fetch timestamps to avoid requiring a Repository instance
 * in simple contexts like WidgetProviders.
 */
object FetchMetadata {
    private const val PREFS_NAME = "weather_fetch_metadata"
    private const val KEY_LAST_FULL_FETCH = "last_full_fetch_time"
    private const val KEY_LAST_CURRENT_TEMP_FETCH = "last_current_temp_fetch_time"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
     * The most recent successful API check of any kind.
     */
    fun getLastSuccessfulCheckTimeMs(context: Context): Long {
        return maxOf(getLastFullFetchTime(context), getLastCurrentTempFetchTime(context))
    }
}
