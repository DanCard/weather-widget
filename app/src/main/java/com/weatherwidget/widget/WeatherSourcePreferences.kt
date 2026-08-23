package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.WeatherSourceOrdering

/**
 * Owns the global visible-source policy, source preference migrations, API keys, and each widget's
 * selected source identity. Persisted selections use stable [WeatherSource.id] values.
 */
internal class WeatherSourcePreferences(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val defaultVisibleSources: List<WeatherSource>,
    private val eventLogger: (String, String) -> Unit = { _, _ -> },
) {
    fun visibleSources(): List<WeatherSource> =
        storedVisibleIds().mapNotNull(::sourceFromStoredId)

    fun primarySource(): WeatherSource = visibleSources().first()

    fun activeDisplaySourceIds(): Set<String> {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, WeatherWidgetProvider::class.java)
        val active = manager.getAppWidgetIds(component).map { currentDisplaySource(it).id }.toSet()
        return active.ifEmpty { setOf(primarySource().id) }
    }

    fun setVisibleSources(sources: List<WeatherSource>) {
        setVisibleSourcesPreservingSelections(
            sources = sources,
            widgetIds = activeWidgetIds(),
            logPrefix = "Order changed",
        )
    }

    fun setVisibleSourcesForSetup(
        sources: List<WeatherSource>,
        widgetIds: IntArray,
    ): Boolean =
        setVisibleSourcesPreservingSelections(
            sources = sources,
            widgetIds = widgetIds,
            logPrefix = "Setup order changed",
        )

    fun isVisible(source: WeatherSource): Boolean = source in visibleSources()

    fun currentDisplaySource(widgetId: Int): WeatherSource {
        val visible = visibleSources()
        val key = displaySourceKey(widgetId)
        val raw = prefs.all[key]
        val decoded = decodeSelection(raw, visible)
        if (raw != null && raw != decoded.id) {
            prefs.edit().putString(key, decoded.id).apply()
        }
        return decoded
    }

    fun nextDisplaySource(widgetId: Int): WeatherSource {
        val visible = visibleSources()
        val current = currentDisplaySource(widgetId)
        val index = visible.indexOf(current).takeIf { it >= 0 } ?: 0
        return visible[(index + 1) % visible.size]
    }

    fun setCurrentDisplaySource(widgetId: Int, source: WeatherSource) {
        if (source in visibleSources()) {
            prefs.edit().putString(displaySourceKey(widgetId), source.id).apply()
        }
    }

    fun toggleDisplaySource(widgetId: Int): WeatherSource {
        val next = nextDisplaySource(widgetId)
        setCurrentDisplaySource(widgetId, next)
        return next
    }

    fun resetToggleState(widgetId: Int) {
        prefs.edit().remove(displaySourceKey(widgetId)).apply()
    }

    fun resetAllToggleStates() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_DISPLAY_SOURCE_PREFIX) }
            .forEach(editor::remove)
        editor.apply()
    }

    fun clearWidget(widgetId: Int, editor: SharedPreferences.Editor) {
        editor.remove(displaySourceKey(widgetId))
    }

    fun apiKey(source: WeatherSource): String? =
        prefs.getString("$KEY_API_KEY_PREFIX${source.name}", null)

    fun setApiKey(source: WeatherSource, apiKey: String?) {
        val editor = prefs.edit()
        if (apiKey.isNullOrBlank()) {
            editor.remove("$KEY_API_KEY_PREFIX${source.name}")
        } else {
            editor.putString("$KEY_API_KEY_PREFIX${source.name}", apiKey)
        }
        editor.apply()
    }

    private fun storedVisibleIds(): List<String> {
        migrateApiPreferenceIfNeeded()
        migrateDeprecatedSourcesIfNeeded()
        migrateSilurianIfNeeded()
        migrateOpenWeatherMapPositionIfNeeded()

        val fallback = defaultVisibleSources.map { it.id }
        val raw = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
        val parsed = raw
            ?.split(",")
            ?.mapNotNull { token -> sourceFromStoredId(token.trim())?.id }
            .orEmpty()
        val sanitized = WeatherSourceOrdering.sanitizeVisibleIds(parsed, fallback)
        val canonical = sanitized.joinToString(",")
        if (raw != canonical) {
            prefs.edit().putString(KEY_VISIBLE_SOURCES_ORDER, canonical).apply()
        }
        return sanitized
    }

    private fun setVisibleSourcesPreservingSelections(
        sources: List<WeatherSource>,
        widgetIds: IntArray,
        logPrefix: String,
    ): Boolean {
        val old = visibleSources()
        val fallback = defaultVisibleSources.map { it.id }
        val newIds = WeatherSourceOrdering.sanitizeVisibleIds(sources.map { it.id }, fallback)
        val new = newIds.mapNotNull(::sourceFromStoredId)
        if (new == old) return false

        val selected = widgetIds.distinct().associateWith(::currentDisplaySource)
        val editor = prefs.edit().putString(KEY_VISIBLE_SOURCES_ORDER, newIds.joinToString(","))
        selected.forEach { (widgetId, oldSource) ->
            val survivor = oldSource.takeIf { it in new } ?: new.first()
            editor.putString(displaySourceKey(widgetId), survivor.id)
        }
        editor.apply()

        val oldNames = old.map { it.name }
        val newNames = new.map { it.name }
        Log.d(TAG, "$logPrefix: $oldNames -> $newNames")
        eventLogger(TAG, "$logPrefix: $oldNames -> $newNames")
        return true
    }

    private fun decodeSelection(raw: Any?, visible: List<WeatherSource>): WeatherSource {
        val fallback = visible.first()
        val decoded = when (raw) {
            is String -> sourceFromStoredId(raw)
            is Int -> visible[raw.mod(visible.size)]
            is Boolean -> visible[if (raw && visible.size > 1) 1 else 0]
            is Number -> visible[raw.toInt().mod(visible.size)]
            else -> null
        }
        return decoded?.takeIf { it in visible } ?: fallback
    }

    private fun migrateApiPreferenceIfNeeded() {
        if (prefs.getBoolean(KEY_API_PREFERENCE_MIGRATION_DONE, false)) return
        if (!prefs.contains(KEY_API_PREFERENCE)) {
            prefs.edit().putBoolean(KEY_API_PREFERENCE_MIGRATION_DONE, true).apply()
            return
        }

        val oldOrdinal = prefs.getInt(KEY_API_PREFERENCE, 1)
        val newOrder = when (oldOrdinal) {
            1 -> "NWS,OPEN_METEO,WEATHER_API"
            2 -> "OPEN_METEO,WEATHER_API,NWS"
            3 -> "WEATHER_API,NWS,OPEN_METEO"
            else -> defaultVisibleSources.joinToString(",") { it.id }
        }
        prefs.edit()
            .putString(KEY_VISIBLE_SOURCES_ORDER, newOrder)
            .putBoolean(KEY_API_PREFERENCE_MIGRATION_DONE, true)
            .remove(KEY_API_PREFERENCE)
            .apply()
        Log.d(TAG, "Migrated legacy API preference ordinal=$oldOrdinal to $newOrder")
        eventLogger(TAG, "Migrated legacy API preference ordinal=$oldOrdinal to $newOrder")
    }

    private fun migrateSilurianIfNeeded() {
        if (prefs.getBoolean(KEY_SILURIAN_MIGRATION_DONE, false)) return
        val current = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
        val editor = prefs.edit().putBoolean(KEY_SILURIAN_MIGRATION_DONE, true)
        if (current != null) {
            val sources = current.split(",")
                .map(String::trim)
                .filter { it.isNotEmpty() && it != "SILURION" }
                .toMutableList()
                .apply {
                    if (WeatherSource.SILURIAN.id !in this) add(WeatherSource.SILURIAN.id)
                }
            editor.putString(KEY_VISIBLE_SOURCES_ORDER, sources.joinToString(","))
        }
        editor.apply()
    }

    private fun migrateDeprecatedSourcesIfNeeded() {
        if (prefs.getBoolean(KEY_DEPRECATED_SOURCE_MIGRATION_DONE, false)) return
        val fallback = defaultVisibleSources.map { it.id }
        val current = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
            ?.split(",")
            .orEmpty()
        val sanitized = WeatherSourceOrdering.sanitizeVisibleIds(current, fallback)
        prefs.edit()
            .putString(KEY_VISIBLE_SOURCES_ORDER, sanitized.joinToString(","))
            .putBoolean(KEY_DEPRECATED_SOURCE_MIGRATION_DONE, true)
            .apply()
        Log.d(TAG, "Removed deprecated sources from visible order: $sanitized")
        eventLogger(TAG, "Removed deprecated sources from visible order: $sanitized")
    }

    private fun migrateOpenWeatherMapPositionIfNeeded() {
        if (prefs.getBoolean(KEY_OPEN_WEATHER_MAP_POSITION_MIGRATION_DONE, false)) return
        val current = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
        val editor = prefs.edit().putBoolean(KEY_OPEN_WEATHER_MAP_POSITION_MIGRATION_DONE, true)
        if (current != null) {
            val sources = current.split(",")
                .map(String::trim)
                .filter { it.isNotEmpty() }
            if (WeatherSource.OPEN_WEATHER_MAP.id in sources && sources.last() != WeatherSource.OPEN_WEATHER_MAP.id) {
                val reordered = sources.filter { it != WeatherSource.OPEN_WEATHER_MAP.id } + WeatherSource.OPEN_WEATHER_MAP.id
                editor.putString(KEY_VISIBLE_SOURCES_ORDER, reordered.joinToString(","))
                Log.d(TAG, "Migrated OPEN_WEATHER_MAP to bottom of visible sources: $reordered")
                eventLogger(TAG, "Migrated OPEN_WEATHER_MAP to bottom of visible sources: $reordered")
            }
        }
        editor.apply()
    }

    private fun activeWidgetIds(): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
    }

    private fun sourceFromStoredId(value: String): WeatherSource? =
        WeatherSource.entries.find { it.id == value || it.name == value || it.displayName == value }

    private fun displaySourceKey(widgetId: Int): String = "$KEY_DISPLAY_SOURCE_PREFIX$widgetId"

    private companion object {
        const val TAG = "SOURCE_ORDER"
        const val KEY_API_PREFERENCE = "api_preference"
        const val KEY_VISIBLE_SOURCES_ORDER = "visible_sources_order"
        const val KEY_API_PREFERENCE_MIGRATION_DONE = "api_pref_migrated"
        const val KEY_SILURIAN_MIGRATION_DONE = "silurian_migration_done_v2"
        const val KEY_DEPRECATED_SOURCE_MIGRATION_DONE = "hide_deprecated_sources_migration_done_v6"
        const val KEY_OPEN_WEATHER_MAP_POSITION_MIGRATION_DONE = "owm_position_bottom_migration_done_v1"
        const val KEY_DISPLAY_SOURCE_PREFIX = "widget_display_source_"
        const val KEY_API_KEY_PREFIX = "api_key_"
    }
}
