package com.weatherwidget.widget

import android.content.SharedPreferences

/**
 * What is left of the retired current-temperature delta record (`widget_current_temp_delta_*`).
 * The header no longer carries a stored delta forward — no reading in the window means the bare
 * forecast, as on desktop (user, 2026-10-24; plans/261024-shared-current-temp-run.md) — so nothing
 * reads or writes the per-source keys. Two jobs remain: the oldest installs kept a widget's
 * coordinates under the un-suffixed lat/lon keys ([legacyLocation], read by [WidgetLocationStore]),
 * and deleting a widget still removes its old keys ([clearWidget]). The keys are not purged
 * otherwise: a purge could not tell a widget's only coordinates from a delta's.
 */
internal class CurrentTemperatureDeltaStore(
    private val prefs: SharedPreferences,
) {
    fun legacyLocation(widgetId: Int): WidgetLocation? {
        val lat = prefs.getString("$KEY_LAT_PREFIX$widgetId", null)?.toDoubleOrNull()
        val lon = prefs.getString("$KEY_LON_PREFIX$widgetId", null)?.toDoubleOrNull()
        return if (lat != null && lon != null) WidgetLocation(lat, lon) else null
    }

    fun clearWidget(widgetId: Int, editor: SharedPreferences.Editor) {
        KEY_PREFIXES.forEach { prefix -> editor.remove("$prefix$widgetId") }
        prefs.all.keys
            .asSequence()
            .filter { key -> KEY_PREFIXES.any { prefix -> key.startsWith("$prefix${widgetId}_") } }
            .forEach(editor::remove)
    }

    private companion object {
        const val KEY_DELTA_PREFIX = "widget_current_temp_delta_"
        const val KEY_OBSERVED_PREFIX = "widget_current_temp_delta_observed_"
        const val KEY_FETCHED_AT_PREFIX = "widget_current_temp_delta_fetched_at_"
        const val KEY_UPDATED_AT_PREFIX = "widget_current_temp_delta_updated_at_"
        const val KEY_SOURCE_PREFIX = "widget_current_temp_delta_source_"
        const val KEY_LAT_PREFIX = "widget_current_temp_delta_lat_"
        const val KEY_LON_PREFIX = "widget_current_temp_delta_lon_"
        val KEY_PREFIXES = listOf(
            KEY_DELTA_PREFIX,
            KEY_OBSERVED_PREFIX,
            KEY_FETCHED_AT_PREFIX,
            KEY_UPDATED_AT_PREFIX,
            KEY_SOURCE_PREFIX,
            KEY_LAT_PREFIX,
            KEY_LON_PREFIX,
        )
    }
}
