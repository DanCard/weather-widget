package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil

internal data class WidgetLocation(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Owns raw per-widget coordinates. Authoritative reads are separate from the legacy fallback so
 * callers that must not act on an inferred location can use [stored].
 */
internal class WidgetLocationStore(
    context: Context,
    private val deltaStore: CurrentTemperatureDeltaStore,
) {
    private val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)

    /**
     * Stored coordinates, or the legacy delta-store copy for installs that predate this file.
     *
     * **No longer falls back to `historical_pois`.** That list is the app's *label* store — it exists
     * so `FriendlyLocationName` can name a coordinate without a network call — and using it as a
     * coordinate source meant "this widget has no location" quietly became "the last place you ever
     * saved." A device-following sample would then compare a fresh fix against a POI, find them the
     * same site, and propose no candidate: the no-location state could never be escaped by GPS, for a
     * user standing exactly where the app could have fixed it. The list itself is untouched.
     */
    fun resolve(widgetId: Int): WidgetLocation? =
        stored(widgetId)
            ?: deltaStore.legacyLocation(widgetId)

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

    /** `commit()`, matching [set] — callers enqueue a refresh immediately after clearing. */
    fun clearWidget(widgetId: Int) {
        widgetPrefs.edit()
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$widgetId")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$widgetId")
            .commit()
    }
}
