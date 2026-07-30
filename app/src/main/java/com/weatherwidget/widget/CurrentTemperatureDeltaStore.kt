package com.weatherwidget.widget

import android.content.SharedPreferences
import com.weatherwidget.data.model.WeatherSource

/** Owns the source-scoped multi-field current-temperature delta preference record. */
internal class CurrentTemperatureDeltaStore(
    private val prefs: SharedPreferences,
) {
    fun get(widgetId: Int, source: WeatherSource): CurrentTemperatureDeltaState? {
        read(deltaStateSuffix(widgetId, source))?.let { return it }

        val legacy = read(widgetId.toString()) ?: return null
        if (legacy.sourceId != source.id) return null

        val editor = prefs.edit()
        put(editor, deltaStateSuffix(widgetId, source), legacy)
        remove(editor, widgetId.toString())
        editor.apply()
        return legacy
    }

    fun set(widgetId: Int, source: WeatherSource, state: CurrentTemperatureDeltaState) {
        val editor = prefs.edit()
        put(editor, deltaStateSuffix(widgetId, source), state)
        editor.apply()
    }

    fun clear(widgetId: Int, source: WeatherSource? = null) {
        val editor = prefs.edit()
        remove(editor, source?.let { deltaStateSuffix(widgetId, it) } ?: widgetId.toString())
        editor.apply()
    }

    fun legacyLocation(widgetId: Int): WidgetLocation? {
        val lat = prefs.getString("$KEY_LAT_PREFIX$widgetId", null)?.toDoubleOrNull()
        val lon = prefs.getString("$KEY_LON_PREFIX$widgetId", null)?.toDoubleOrNull()
        return if (lat != null && lon != null) WidgetLocation(lat, lon) else null
    }

    fun clearWidget(widgetId: Int, editor: SharedPreferences.Editor) {
        remove(editor, widgetId.toString())
        prefs.all.keys
            .asSequence()
            .filter { key -> KEY_PREFIXES.any { prefix -> key.startsWith("$prefix${widgetId}_") } }
            .forEach(editor::remove)
    }

    private fun put(
        editor: SharedPreferences.Editor,
        suffix: String,
        state: CurrentTemperatureDeltaState,
    ) {
        editor
            .putFloat("$KEY_DELTA_PREFIX$suffix", state.delta)
            .putFloat("$KEY_OBSERVED_PREFIX$suffix", state.lastObservedTemp)
            .putLong("$KEY_FETCHED_AT_PREFIX$suffix", state.lastObservedAt)
            .putLong("$KEY_UPDATED_AT_PREFIX$suffix", state.updatedAtMs)
            .putString("$KEY_SOURCE_PREFIX$suffix", state.sourceId)
            .putString("$KEY_LAT_PREFIX$suffix", state.locationLat.toString())
            .putString("$KEY_LON_PREFIX$suffix", state.locationLon.toString())
    }

    private fun remove(editor: SharedPreferences.Editor, suffix: String) {
        KEY_PREFIXES.forEach { prefix -> editor.remove("$prefix$suffix") }
    }

    private fun read(suffix: String): CurrentTemperatureDeltaState? {
        val deltaKey = "$KEY_DELTA_PREFIX$suffix"
        val observedKey = "$KEY_OBSERVED_PREFIX$suffix"
        val fetchedAtKey = "$KEY_FETCHED_AT_PREFIX$suffix"
        if (!prefs.contains(deltaKey) || !prefs.contains(observedKey) || !prefs.contains(fetchedAtKey)) {
            return null
        }

        val sourceId = prefs.getString("$KEY_SOURCE_PREFIX$suffix", null) ?: return null
        val lat = prefs.getString("$KEY_LAT_PREFIX$suffix", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("$KEY_LON_PREFIX$suffix", null)?.toDoubleOrNull() ?: return null
        return CurrentTemperatureDeltaState(
            delta = prefs.getFloat(deltaKey, 0f),
            lastObservedTemp = prefs.getFloat(observedKey, 0f),
            lastObservedAt = prefs.getLong(fetchedAtKey, 0L),
            updatedAtMs = prefs.getLong("$KEY_UPDATED_AT_PREFIX$suffix", 0L),
            sourceId = sourceId,
            locationLat = lat,
            locationLon = lon,
        )
    }

    private fun deltaStateSuffix(widgetId: Int, source: WeatherSource): String =
        "${widgetId}_${source.id}"

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
