package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.util.SharedPreferencesUtil

object ActiveLocationResolver {
    private const val PREFS_NAME = "active_weather_location"
    private const val KEY_LAT = "latitude"
    private const val KEY_LON = "longitude"

    fun current(context: Context): Pair<Double, Double>? {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        val lat = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
        val lon = prefs.getFloat(KEY_LON, Float.NaN).toDouble()
        if (!lat.isFinite() || !lon.isFinite()) return null
        return lat to lon
    }

    fun persist(context: Context, lat: Double, lon: Double) {
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
            .edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LON, lon.toFloat())
            .commit()
    }

    /**
     * Drops the canonical active location, returning the app to the "no location" state. Used by
     * [LegacyDefaultLocationMigration] and by the ConfigActivity paths that used to persist the
     * Google-HQ placeholder.
     */
    fun clear(context: Context) {
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit().clear().commit()
    }

    internal fun clearForTesting(context: Context) = clear(context)

    suspend fun resolve(
        context: Context,
        stateManager: WidgetStateManager,
        forecastDao: ForecastDao
    ): Pair<Double, Double> {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        current(context)?.let { canonical ->
            syncCompatibilityCopies(stateManager, appWidgetIds, canonical)
            return canonical
        }

        val configuredLocation = appWidgetIds.toList().firstNotNullOfOrNull { id ->
            stateManager.getStoredWidgetLocation(id)
        }
        val resolved = configuredLocation
            ?: forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }
            ?: (WeatherWidgetWorker.DEFAULT_LAT to WeatherWidgetWorker.DEFAULT_LON)
        // One-time migration for installs that predate the canonical app-wide location.
        persist(context, resolved.first, resolved.second)
        syncCompatibilityCopies(stateManager, appWidgetIds, resolved)
        return resolved
    }

    private fun syncCompatibilityCopies(
        stateManager: WidgetStateManager,
        appWidgetIds: IntArray,
        canonical: Pair<Double, Double>,
    ) {
        if (appWidgetIds.any { stateManager.getStoredWidgetLocation(it) != canonical }) {
            stateManager.setWidgetLocations(appWidgetIds, canonical.first, canonical.second)
        }
    }
}
