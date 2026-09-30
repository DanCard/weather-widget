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
    private const val KEY_LABEL = "chosen_label"

    fun current(context: Context): Pair<Double, Double>? {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        val lat = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
        val lon = prefs.getFloat(KEY_LON, Float.NaN).toDouble()
        if (!lat.isFinite() || !lon.isFinite()) return null
        return lat to lon
    }

    /**
     * The name the user picked on the setup screen for the active location ("860 Avery Drive,
     * Mountain View, CA 94043"), or null when the active location was not chosen by name — a
     * follow-device move, a migration, or a save that had no name. Settings shows it in preference
     * to the reverse-geocoded city, which is all [com.weatherwidget.util.FriendlyLocationName] knows.
     */
    fun chosenLabel(context: Context): String? =
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }

    /**
     * [chosenLabel] travels with the coordinates: every write replaces both, so a label can never
     * outlive the site it names.
     */
    fun persist(context: Context, lat: Double, lon: Double, chosenLabel: String? = null) {
        val editor = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
            .edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LON, lon.toFloat())
        if (chosenLabel.isNullOrBlank()) editor.remove(KEY_LABEL) else editor.putString(KEY_LABEL, chosenLabel)
        editor.commit()
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

    /**
     * The app-wide location to fetch and render at, or **null when there is genuinely none** — no
     * canonical active location, no configured widget location, no cached weather.
     *
     * Null is a real answer, not a failure to be papered over. This used to fall back to Google HQ,
     * so a user whose GPS never resolved had live weather fetched for Mountain View and labelled as
     * theirs. Callers must gate on null (the worker renders the no-location state) rather than
     * substituting a coordinate of their own.
     */
    suspend fun resolve(
        context: Context,
        stateManager: WidgetStateManager,
        forecastDao: ForecastDao
    ): Pair<Double, Double>? {
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
        // The cached-weather fallback is the only location record an install predating the canonical
        // active location has, so it stays — except in one bounded window. Between
        // LegacyDefaultLocationMigration clearing the Google-HQ sentinel from prefs and the worker
        // purging the forecast rows filed at it, those rows would hand the sentinel straight back and
        // this method would re-persist it as canonical. That is how v1 of the migration silently
        // undid itself. One prefs read; false for every install that has been through the purge.
        val cachedWeatherLocation = if (LegacyDefaultLocationMigration.isPurgePending(context)) {
            null
        } else {
            forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }
        }
        val resolved = configuredLocation
            ?: cachedWeatherLocation
            ?: return null
        // One-time migration for installs that predate the canonical app-wide location. Only ever
        // writes a location we actually resolved — never a placeholder.
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
