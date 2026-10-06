package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.util.SourceCoverage
import com.weatherwidget.shared.util.WeatherSourceOrdering

/**
 * Owns the global visible-source policy, source preference migrations, API keys, and each widget's
 * selected source identity. Persisted selections use stable [WeatherSource.id] values.
 *
 * Two lists, deliberately distinct:
 * - **enabled** ([enabledSources]) — the user's choices, as persisted. Only the user changes it
 *   (Settings; the setup screen's WeatherAPI add). A location never edits it.
 * - **visible** ([visibleSources]) — enabled minus what cannot serve the active location
 *   ([SourceCoverage]). Everything that fetches, displays, or cycles reads this one.
 * The visible list used to be the enabled list with NWS *removed* outside coverage, plus a marker to
 * put it back; a lost marker left NWS off for good (2026-09-29).
 */
internal class WeatherSourcePreferences(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val defaultVisibleSources: List<WeatherSource>,
    private val eventLogger: (String, String) -> Unit = { _, _ -> },
    private val activeLocation: () -> Pair<Double, Double>? = { ActiveLocationResolver.current(context) },
) {
    /** The user's enabled sources, in their order. Settings reads and writes this. */
    fun enabledSources(): List<WeatherSource> =
        storedVisibleIds().mapNotNull(::sourceFromStoredId)

    /** [enabledSources] minus sources that cannot serve the active location. Never empty. */
    fun visibleSources(): List<WeatherSource> {
        val location = activeLocation()
        return SourceCoverage.effectiveSources(storedVisibleIds(), location?.first, location?.second)
            .mapNotNull(::sourceFromStoredId)
    }

    /** Enabled, but not usable at the active location — Settings shows it checked, marked unavailable. */
    fun isUnavailableHere(source: WeatherSource): Boolean {
        val location = activeLocation()
        return !SourceCoverage.supports(source.id, location?.first, location?.second)
    }

    fun primarySource(): WeatherSource = visibleSources().first()

    fun activeDisplaySourceIds(): Set<String> {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, WeatherWidgetProvider::class.java)
        val active = manager.getAppWidgetIds(component).map { currentDisplaySource(it).id }.toSet()
        return active.ifEmpty { setOf(primarySource().id) }
    }

    /** The Settings screen's writer. */
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

    /**
     * The source [widgetId] shows. The stored selection is canonicalised against the **enabled**
     * list only: a widget set to NWS shows the fallback in Warsaw, but its stored choice stays NWS,
     * so it shows NWS again in Mountain View. Persisting the location-driven fallback would make a
     * trip abroad permanently change what the widget displays.
     */
    fun currentDisplaySource(widgetId: Int): WeatherSource {
        val key = displaySourceKey(widgetId)
        val raw = prefs.all[key]
        val stored = decodeSelection(raw, enabledSources())
        if (raw != null && raw != stored.id) {
            prefs.edit().putString(key, stored.id).apply()
        }
        val visible = visibleSources()
        return stored.takeIf { it in visible } ?: visible.first()
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

    /**
     * Which feed supplies [source]'s actuals, when the user has chosen one.
     *
     * Only meaningful for a forecast-only source, which has no observations of its own and borrows
     * (see `ActualsProviderResolver`). Null means "no choice stored" and resolves to the default.
     * A stored id that no longer names a usable provider is dropped here rather than returned, so a
     * source removed from the app cannot leave a borrower pointing at nothing.
     */
    fun actualsProvider(source: WeatherSource): WeatherSource? =
        prefs.getString("$KEY_ACTUALS_PROVIDER_PREFIX${source.name}", null)
            ?.let { stored -> WeatherSource.entries.firstOrNull { it.id == stored } }
            ?.takeIf { ActualsProviderResolver.canProvide(it) }

    /** Passing null clears the choice, restoring the default. */
    fun setActualsProvider(source: WeatherSource, provider: WeatherSource?) {
        val key = "$KEY_ACTUALS_PROVIDER_PREFIX${source.name}"
        prefs.edit().apply {
            if (provider == null) remove(key) else putString(key, provider.id)
        }.apply()
    }

    private fun storedVisibleIds(): List<String> {
        migrateApiPreferenceIfNeeded()
        migrateDeprecatedSourcesIfNeeded()
        migrateSilurianIfNeeded()
        migrateOpenWeatherMapPositionIfNeeded()
        migrateNwsReenabledIfNeeded()
        migrateGoogleWeatherEnabledIfNeeded()

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
        val old = enabledSources()
        val fallback = defaultVisibleSources.map { it.id }
        val newIds = WeatherSourceOrdering.sanitizeVisibleIds(sources.map { it.id }, fallback)
        val new = newIds.mapNotNull(::sourceFromStoredId)
        if (new == old) return false

        val selected = widgetIds.distinct().associateWith(::storedDisplaySource)
        val oldIds = old.map { it.id }
        val editor = prefs.edit().putString(KEY_VISIBLE_SOURCES_ORDER, newIds.joinToString(","))
        selected.forEach { (widgetId, oldSource) ->
            editor.putString(
                displaySourceKey(widgetId),
                WeatherSourceOrdering.selectionAfterChange(oldIds, newIds, oldSource.id),
            )
        }
        editor.apply()

        val oldNames = old.map { it.name }
        val newNames = new.map { it.name }
        Log.d(TAG, "$logPrefix: $oldNames -> $newNames")
        eventLogger(TAG, "$logPrefix: $oldNames -> $newNames")
        return true
    }

    /** The widget's stored choice, resolved against the enabled list (not the location-filtered one). */
    private fun storedDisplaySource(widgetId: Int): WeatherSource =
        decodeSelection(prefs.all[displaySourceKey(widgetId)], enabledSources())

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

    /**
     * One-time: put NWS back in the enabled list. Until 2026-09-29 the app removed NWS from that list
     * outside its coverage, and a lost "the app did it" marker (an older build, any Settings save)
     * left it off for good. The app cannot tell such a removal from a real untick, so this re-enables
     * a deliberate untick once — the accepted trade-off. Coverage is now [SourceCoverage]'s job.
     */
    private fun migrateNwsReenabledIfNeeded() {
        if (prefs.getBoolean(KEY_NWS_REENABLED_MIGRATION_DONE, false)) return
        val current = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
        val editor = prefs.edit()
            .putBoolean(KEY_NWS_REENABLED_MIGRATION_DONE, true)
            .remove(KEY_NWS_AUTO_RETIRED_LEGACY)
        val ids = current?.split(",")?.map(String::trim)?.filter { it.isNotEmpty() }
        val outcome = if (ids != null && WeatherSource.NWS.id !in ids) {
            editor.putString(KEY_VISIBLE_SOURCES_ORDER, (listOf(WeatherSource.NWS.id) + ids).joinToString(","))
            "restored"
        } else {
            "already_present"
        }
        editor.apply()
        Log.d(TAG, "NWS re-enable migration: outcome=$outcome")
        eventLogger("NWS_ENABLED_MIGRATION", "outcome=$outcome")
    }

    /**
     * One-time, for builds whose defaults include Google (debug only — the only builds with a baked
     * key): enable it on an install that already has a stored list, as primary, and switch every
     * widget to it. Flag-gated like the Silurian migration, so a later untick sticks.
     */
    private fun migrateGoogleWeatherEnabledIfNeeded() {
        if (WeatherSource.GOOGLE_WEATHER !in defaultVisibleSources) return
        if (prefs.getBoolean(KEY_GOOGLE_WEATHER_ENABLED_MIGRATION_DONE, false)) return
        val current = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
        val editor = prefs.edit().putBoolean(KEY_GOOGLE_WEATHER_ENABLED_MIGRATION_DONE, true)
        val ids = current?.split(",")?.map(String::trim)?.filter { it.isNotEmpty() }
        val outcome = if (ids != null && WeatherSource.GOOGLE_WEATHER.id !in ids) {
            editor.putString(
                KEY_VISIBLE_SOURCES_ORDER,
                WeatherSourceOrdering.withEnabled(ids, WeatherSource.GOOGLE_WEATHER).joinToString(","),
            )
            activeWidgetIds().forEach { editor.putString(displaySourceKey(it), WeatherSource.GOOGLE_WEATHER.id) }
            // The widgets now show a source with no data. This runs inside a prefs read (any thread,
            // possibly a worker), so it only marks the switch; the startup paint starts the fetch
            // and banner (SourceSwitchFetch via WidgetStartupCoordinator).
            editor.putString(KEY_PENDING_SOURCE_SWITCH, WeatherSource.GOOGLE_WEATHER.id)
            "enabled_primary"
        } else {
            "unchanged"
        }
        editor.apply()
        Log.d(TAG, "Google Weather debug enable migration: outcome=$outcome")
        eventLogger(TAG, "Google Weather debug enable migration: outcome=$outcome")
    }

    /** A source switch the debug migration made and nobody has fetched for yet; cleared on read. */
    fun consumePendingSourceSwitch(): WeatherSource? {
        val id = prefs.getString(KEY_PENDING_SOURCE_SWITCH, null) ?: return null
        prefs.edit().remove(KEY_PENDING_SOURCE_SWITCH).apply()
        return WeatherSource.entries.firstOrNull { it.id == id }
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
        /** Written by builds before 2026-09-29; removed by [migrateNwsReenabledIfNeeded]. */
        const val KEY_NWS_AUTO_RETIRED_LEGACY = "nws_auto_retired"
        const val KEY_NWS_REENABLED_MIGRATION_DONE = "nws_reenabled_migration_done_v1"
        const val KEY_API_PREFERENCE_MIGRATION_DONE = "api_pref_migrated"
        const val KEY_SILURIAN_MIGRATION_DONE = "silurian_migration_done_v2"
        const val KEY_DEPRECATED_SOURCE_MIGRATION_DONE = "hide_deprecated_sources_migration_done_v6"
        const val KEY_OPEN_WEATHER_MAP_POSITION_MIGRATION_DONE = "owm_position_bottom_migration_done_v1"
        const val KEY_GOOGLE_WEATHER_ENABLED_MIGRATION_DONE = "google_weather_debug_enabled_migration_done_v1"
        const val KEY_PENDING_SOURCE_SWITCH = "pending_source_switch_fetch"
        const val KEY_DISPLAY_SOURCE_PREFIX = "widget_display_source_"
        const val KEY_API_KEY_PREFIX = "api_key_"
        const val KEY_ACTUALS_PROVIDER_PREFIX = "actuals_provider_"
    }
}
