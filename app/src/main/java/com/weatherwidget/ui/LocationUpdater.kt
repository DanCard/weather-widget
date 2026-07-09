package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.R
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.util.LocationMode
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager

/**
 * Single source of truth for applying a chosen location to every placed widget. Shared by the
 * manual coordinate entry in [SettingsActivity] and the GPS auto-heal in [MainActivity] so both
 * follow the identical, supported propagation path (widget prefs + historical POI + force refresh).
 */
object LocationUpdater {

    fun getWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, WeatherWidgetProvider::class.java),
        )

    /**
     * Checks if any widget location needs to be auto-healed to the fresh location (i.e. is not same-site).
     */
    fun shouldHealTo(context: Context, freshLat: Double, freshLon: Double): Boolean {
        val ids = getWidgetIds(context)
        if (ids.isEmpty()) return false
        val stateManager = WidgetStateManager(context)
        return ids.any { id ->
            val loc = stateManager.getWidgetLocation(id) ?: (WeatherWidgetWorker.DEFAULT_LAT to WeatherWidgetWorker.DEFAULT_LON)
            !LocationMatch.sameSite(loc.first, loc.second, freshLat, freshLon)
        }
    }

    /**
     * True when every placed widget is still pinned to the hard default coordinates (or has no
     * location set yet). This is the signal that GPS never resolved — the auto-heal condition.
     * Returns false when there are no widgets (nothing to heal).
     */
    fun allWidgetsAtDefault(context: Context): Boolean {
        val ids = getWidgetIds(context)
        if (ids.isEmpty()) return false
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val defaultLat = WeatherWidgetWorker.DEFAULT_LAT.toFloat()
        val defaultLon = WeatherWidgetWorker.DEFAULT_LON.toFloat()
        return ids.all { id ->
            val lat = prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", Float.NaN)
            val lon = prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$id", Float.NaN)
            (lat.isNaN() || lon.isNaN()) || (lat == defaultLat && lon == defaultLon)
        }
    }

    /**
     * Human-readable summary of the effective location (first widget → last historical POI →
     * hard default) plus whether it's pinned or follows the device. Shown on both the Settings
     * screen and the location setup screen ([ConfigActivity]).
     */
    fun describeCurrentLocation(context: Context): String {
        var currentLat: Double? = null
        var currentLon: Double? = null
        var labelText = context.getString(R.string.no_location_set)

        val ids = getWidgetIds(context)
        if (ids.isNotEmpty()) {
            val widgetLocation = WidgetStateManager(context).getWidgetLocation(ids[0])
            if (widgetLocation != null) {
                currentLat = widgetLocation.first
                currentLon = widgetLocation.second
                labelText = context.getString(
                    R.string.widget_location_format,
                    String.format("%.4f", currentLat),
                    String.format("%.4f", currentLon),
                )
            }
        }

        if (currentLat == null || currentLon == null) {
            // Fallback to historical_pois default POI
            val weatherPrefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
            val lastPoi = weatherPrefs.getString("historical_pois", null)
                ?.split(";")
                ?.lastOrNull()
                ?.split("|")
                ?.takeLast(3)
                ?.let { parts ->
                    if (parts.size == 3) {
                        parts[1].toDoubleOrNull()?.let { lat -> parts[2].toDoubleOrNull()?.let { lon -> lat to lon } }
                    } else {
                        null
                    }
                }
            if (lastPoi != null) {
                currentLat = lastPoi.first
                currentLon = lastPoi.second
                labelText = context.getString(
                    R.string.default_location_format,
                    String.format("%.4f", currentLat),
                    String.format("%.4f", currentLon),
                )
            }
        }

        if (currentLat == null || currentLon == null) {
            labelText = context.getString(
                R.string.default_location_format,
                String.format("%.4f", WeatherWidgetWorker.DEFAULT_LAT),
                String.format("%.4f", WeatherWidgetWorker.DEFAULT_LON),
            )
        }

        val modeSuffix = if (LocationMode.get(context) == LocationMode.FIXED) {
            context.getString(R.string.location_mode_pinned)
        } else {
            context.getString(R.string.location_mode_follow)
        }
        return "$labelText • $modeSuffix"
    }

    /**
     * Writes [lat]/[lon] to all widgets, records the POI, and force-refreshes. Mirrors the path that
     * the Settings "save location" button has always used. [ids] defaults to every placed widget;
     * tests pass synthetic ids so they never rewrite a real widget's configured location.
     */
    fun applyToAllWidgets(
        context: Context,
        lat: Double,
        lon: Double,
        label: String,
        ids: IntArray = getWidgetIds(context),
    ) {
        // 1. Update all widgets' configured location.
        val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val editor = widgetPrefs.edit()
        for (id in ids) {
            editor.putFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", lat.toFloat())
            editor.putFloat("${ConfigActivity.KEY_LON_PREFIX}$id", lon.toFloat())
        }
        editor.apply()

        // 2. Update default POI in weather_prefs (the current-temp fetch loop reads this).
        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        val historicalPois = weatherPrefs.getString("historical_pois", null)
        val newPoi = "$label|$lat|$lon"
        val updatedPois = if (historicalPois.isNullOrBlank()) {
            newPoi
        } else {
            val pois = historicalPois.split(";").toMutableList()
            pois.removeAll { it.contains("|$lat|$lon") || it.startsWith("$label|") }
            pois.add(newPoi)
            pois.takeLast(5).joinToString(";")
        }
        weatherPrefs.edit().putString("historical_pois", updatedPois).apply()

        // 3. Trigger a forced widget refresh for the new location.
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
