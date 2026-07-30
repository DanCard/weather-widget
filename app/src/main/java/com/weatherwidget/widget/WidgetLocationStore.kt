package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil

internal data class WidgetLocation(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Owns raw per-widget coordinates. Authoritative reads are separate from legacy/global fallbacks so
 * callers that must not fetch at an inferred location can use [stored].
 */
internal class WidgetLocationStore(
    context: Context,
    private val deltaStore: CurrentTemperatureDeltaStore,
) {
    private val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
    private val weatherPrefs = SharedPreferencesUtil.getPrefs(context, WEATHER_PREFS_NAME)

    fun resolve(widgetId: Int): WidgetLocation? =
        stored(widgetId)
            ?: deltaStore.legacyLocation(widgetId)
            ?: historicalPoiFallback()

    fun stored(widgetId: Int): WidgetLocation? {
        val latKey = "${ConfigActivity.KEY_LAT_PREFIX}$widgetId"
        val lonKey = "${ConfigActivity.KEY_LON_PREFIX}$widgetId"
        if (!widgetPrefs.contains(latKey) || !widgetPrefs.contains(lonKey)) return null
        val lat = widgetPrefs.getFloat(latKey, Float.NaN)
        val lon = widgetPrefs.getFloat(lonKey, Float.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return WidgetLocation(lat.toDouble(), lon.toDouble())
    }

    fun set(widgetIds: IntArray, latitude: Double, longitude: Double) {
        val editor = widgetPrefs.edit()
        widgetIds.forEach { widgetId ->
            editor.putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", latitude.toFloat())
            editor.putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", longitude.toFloat())
        }
        editor.commit()
    }

    fun clearWidget(widgetId: Int) {
        widgetPrefs.edit()
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$widgetId")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$widgetId")
            .apply()
    }

    private fun historicalPoiFallback(): WidgetLocation? =
        weatherPrefs.getString(KEY_HISTORICAL_POIS, null)
            ?.split("|")
            ?.takeLast(3)
            ?.takeIf { it.size == 3 }
            ?.let { parts ->
                val lat = parts[1].toDoubleOrNull() ?: return@let null
                val lon = parts[2].toDoubleOrNull() ?: return@let null
                WidgetLocation(lat, lon)
            }

    private companion object {
        const val WEATHER_PREFS_NAME = "weather_prefs"
        const val KEY_HISTORICAL_POIS = "historical_pois"
    }
}
