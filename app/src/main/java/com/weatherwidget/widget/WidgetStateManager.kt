package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ConfigActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ViewMode {
    DAILY, // Default: shows daily forecast bars
    TEMPERATURE, // Alternative: shows hourly temperature curve
    PRECIPITATION, // Hourly precipitation probability graph
    CLOUD_COVER, // Hourly cloud cover percentage graph
}

enum class ZoomLevel(
    val backHours: Long,
    val forwardHours: Long,
    val navJump: Int,
    val labelInterval: Int,
    val precipSmoothIterations: Int,
) {
    WIDE(backHours = 12, forwardHours = 12, navJump = 6, labelInterval = 4, precipSmoothIterations = 2),
    NARROW(backHours = 2, forwardHours = 2, navJump = 2, labelInterval = 1, precipSmoothIterations = 0),
}

@Singleton
class WidgetStateManager
    @Inject
    constructor(
        private val context: Context,
        private val appLogDao: AppLogDao? = null,
    ) {
        private val logScope = CoroutineScope(Dispatchers.IO)
        
        private fun logEvent(tag: String, message: String) {
            appLogDao?.let { dao ->
                logScope.launch {
                    dao.log(tag, message)
                }
            }
        }
        companion object {
            private const val PREFS_NAME = "widget_state_prefs"
            const val DEFAULT_TEST_PREFS_NAME = "widget_state_prefs_android_test"
            @Volatile
            private var prefsNameOverride: String? = null
            private const val KEY_DATE_OFFSET_PREFIX = "widget_date_offset_"
            private const val KEY_API_PREFERENCE = "api_preference"
            private const val KEY_VISIBLE_SOURCES_ORDER = "visible_sources_order"
            private const val KEY_MIGRATION_DONE = "api_pref_migrated"
            private const val DEFAULT_VISIBLE_SOURCES = "NWS,WEATHER_API,OPEN_METEO"
            private const val KEY_DISPLAY_SOURCE_PREFIX = "widget_display_source_"
            private const val KEY_VIEW_MODE_PREFIX = "widget_view_mode_"
            private const val KEY_HOURLY_OFFSET_PREFIX = "widget_hourly_offset_"
            private const val KEY_RAIN_SHOWN_DATE_PREFIX = "widget_rain_shown_date_"
            private const val KEY_ZOOM_LEVEL_PREFIX = "widget_zoom_level_"
            private const val KEY_CURRENT_TEMP_DELTA_PREFIX = "widget_current_temp_delta_"
            private const val KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX = "widget_current_temp_delta_observed_"
            private const val KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX = "widget_current_temp_delta_fetched_at_"
            private const val KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX = "widget_current_temp_delta_updated_at_"
            private const val KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX = "widget_current_temp_delta_source_"
            private const val KEY_CURRENT_TEMP_DELTA_LAT_PREFIX = "widget_current_temp_delta_lat_"
            private const val KEY_CURRENT_TEMP_DELTA_LON_PREFIX = "widget_current_temp_delta_lon_"
            private const val KEY_MISSING_DATA_REFRESH_PREFIX = "widget_missing_data_refresh_"

            const val MIN_DATE_OFFSET = -30 // Last 30 days of history
            const val MAX_DATE_OFFSET = 14 // 14 days forward
            const val MIN_HOURLY_OFFSET = -24 // Allow scrolling to see history (12h default + 12h scroll)
            // Keep this aligned with daily navigation horizon so day-click to precip can
            // preserve the intended target day (up to +14 days).
            const val MAX_HOURLY_OFFSET = 336 // 14 days into the future
            const val HOURLY_NAV_JUMP = 6 // Navigate in 6-hour chunks (default, use getNavJump for zoom-aware)

            @Deprecated("Use WeatherSource.NWS.displayName instead", ReplaceWith("WeatherSource.NWS.displayName"))
            const val SOURCE_NWS = "NWS"

            @Deprecated("Use WeatherSource.OPEN_METEO.displayName instead", ReplaceWith("WeatherSource.OPEN_METEO.displayName"))
            const val SOURCE_OPEN_METEO = "Open-Meteo"

            @Deprecated("Use WeatherSource.WEATHER_API.displayName instead", ReplaceWith("WeatherSource.WEATHER_API.displayName"))
            const val SOURCE_WEATHER_API = "WeatherAPI"

            @Deprecated("Use WeatherSource.GENERIC_GAP.id instead", ReplaceWith("WeatherSource.GENERIC_GAP.id"))
            const val SOURCE_GENERIC_GAP = "Generic"

            @Synchronized
            fun setPrefsNameOverrideForTesting(prefsName: String?) {
                prefsNameOverride = prefsName
            }

            fun getPrefsNameForTesting(): String = prefsNameOverride ?: PREFS_NAME
        }

        private val prefs: SharedPreferences by lazy {
            com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, prefsNameOverride ?: PREFS_NAME)
        }

        fun getDateOffset(widgetId: Int): Int {
            return prefs.getInt("$KEY_DATE_OFFSET_PREFIX$widgetId", 0)
        }

        fun setDateOffset(
            widgetId: Int,
            offset: Int,
        ) {
            val clampedOffset = offset.coerceIn(MIN_DATE_OFFSET, MAX_DATE_OFFSET)
            prefs.edit().putInt("$KEY_DATE_OFFSET_PREFIX$widgetId", clampedOffset).apply()
        }

        fun navigateLeft(widgetId: Int): Int {
            val currentOffset = getDateOffset(widgetId)
            val newOffset = (currentOffset - 1).coerceAtLeast(MIN_DATE_OFFSET)
            setDateOffset(widgetId, newOffset)
            return newOffset
        }

        fun navigateRight(widgetId: Int): Int {
            val currentOffset = getDateOffset(widgetId)
            val newOffset = (currentOffset + 1).coerceAtMost(MAX_DATE_OFFSET)
            setDateOffset(widgetId, newOffset)
            return newOffset
        }

        fun canNavigateLeft(widgetId: Int): Boolean {
            return getDateOffset(widgetId) > MIN_DATE_OFFSET
        }

        fun canNavigateRight(widgetId: Int): Boolean {
            return getDateOffset(widgetId) < MAX_DATE_OFFSET
        }

        fun resetDateOffset(widgetId: Int) {
            setDateOffset(widgetId, 0)
        }

        /**
         * Returns the ordered list of visible weather sources.
         * Sources in this list are shown in the widget toggle cycle (in order).
         * Sources NOT in this list are hidden from the toggle but still fetch when charging.
         */
        fun getVisibleSourcesOrder(): List<WeatherSource> {
            return getStoredVisibleSourcesOrder()
        }

        private fun getStoredVisibleSourcesOrder(): List<WeatherSource> {
            migrateApiPreferenceIfNeeded()
            migrateSilurianIfNeeded()
            val raw = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, DEFAULT_VISIBLE_SOURCES) ?: DEFAULT_VISIBLE_SOURCES
            val sources = raw.split(",")
                .mapNotNull { id ->
                    try { WeatherSource.valueOf(id.trim()) } catch (_: Exception) {
                        WeatherSource.entries.find { it.id == id.trim() || it.name == id.trim() }
                    }
                }
                .filter { it != WeatherSource.GENERIC_GAP }
                .distinct()
            return sources.ifEmpty { listOf(WeatherSource.NWS) }
        }

        fun getEffectiveVisibleSourcesOrder(
            latitude: Double,
            longitude: Double,
        ): List<WeatherSource> {
            return getStoredVisibleSourcesOrder()
        }

        fun getEffectiveVisibleSourcesOrder(widgetId: Int): List<WeatherSource> {
            val location = getWidgetLocation(widgetId)
            return if (location != null) {
                getEffectiveVisibleSourcesOrder(location.first, location.second)
            } else {
                getStoredVisibleSourcesOrder()
            }
        }

        /** Ensures SILURIAN is injected into existing source lists using the default trailing position. */
        private fun migrateSilurianIfNeeded() {
            if (prefs.getBoolean("silurian_migration_done_v2", false)) return
            
            val currentOrder = prefs.getString(KEY_VISIBLE_SOURCES_ORDER, null)
            if (currentOrder != null) {
                // Remove any old typos or duplicates, then add SILURIAN at the end.
                val sources = currentOrder.split(",")
                    .map { it.trim() }
                    .filter { it != "SILURION" && it != "SILURIAN" }
                    .toMutableList()
                
                sources.add("SILURIAN")
                val newOrder = sources.joinToString(",")
                
                prefs.edit()
                    .putString(KEY_VISIBLE_SOURCES_ORDER, newOrder)
                    .putBoolean("silurian_migration_done_v2", true)
                    .apply()
                Log.d("SOURCE_ORDER", "migrateSilurian: Injected SILURIAN into order: $newOrder")
            } else {
                prefs.edit().putBoolean("silurian_migration_done_v2", true).apply()
            }
        }

        fun setVisibleSourcesOrder(sources: List<WeatherSource>) {
            val oldOrder = getVisibleSourcesOrder().map { it.name }
            val filtered = sources.filter { it != WeatherSource.GENERIC_GAP }
            val csv = filtered.joinToString(",") { it.name }
            val newOrder = filtered.map { it.name }
            Log.d("SOURCE_ORDER", "setVisibleSourcesOrder: $oldOrder -> $newOrder")
            appLogDao?.let { dao ->
                logScope.launch { dao.log("SOURCE_ORDER", "Order changed: $oldOrder -> $newOrder") }
            }
            prefs.edit().putString(KEY_VISIBLE_SOURCES_ORDER, csv).apply()
            // Reset all widget toggle states so they start at position 0 of the new order
            resetAllToggleStates()
        }

        fun isSourceVisible(source: WeatherSource): Boolean {
            return source in getVisibleSourcesOrder()
        }

        fun isSourceVisible(
            source: WeatherSource,
            latitude: Double,
            longitude: Double,
        ): Boolean = source in getEffectiveVisibleSourcesOrder(latitude, longitude)

        /**
         * One-time migration from old api_preference int (ordinal of the removed ApiPreference enum)
         * to the new visible_sources_order string.
         * Ordinals: 0=ALTERNATE, 1=PREFER_NWS, 2=PREFER_OPENMETEO, 3=PREFER_WEATHERAPI
         */
        private fun migrateApiPreferenceIfNeeded() {
            if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return
            if (!prefs.contains(KEY_API_PREFERENCE)) {
                prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
                return
            }

            val oldOrdinal = prefs.getInt(KEY_API_PREFERENCE, 1)
            val newOrder = when (oldOrdinal) {
                1 -> "NWS,OPEN_METEO,WEATHER_API"       // PREFER_NWS
                2 -> "OPEN_METEO,WEATHER_API,NWS"        // PREFER_OPENMETEO
                3 -> "WEATHER_API,NWS,OPEN_METEO"        // PREFER_WEATHERAPI
                else -> DEFAULT_VISIBLE_SOURCES           // ALTERNATE (0) or unknown
            }
            Log.d("SOURCE_ORDER", "migrateApiPreference: ordinal=$oldOrdinal -> order=$newOrder")
            logScope.launch {
                appLogDao?.log("SOURCE_ORDER", "Migration from ordinal=$oldOrdinal -> order=$newOrder")
            }
            prefs.edit()
                .putString(KEY_VISIBLE_SOURCES_ORDER, newOrder)
                .putBoolean(KEY_MIGRATION_DONE, true)
                .remove(KEY_API_PREFERENCE)
                .apply()
        }

        fun clearWidgetState(widgetId: Int) {
            val editor = prefs.edit()
            editor
                .remove("$KEY_DATE_OFFSET_PREFIX$widgetId")
                .remove("$KEY_DISPLAY_SOURCE_PREFIX$widgetId")
                .remove("$KEY_VIEW_MODE_PREFIX$widgetId")
                .remove("$KEY_HOURLY_OFFSET_PREFIX$widgetId")
                .remove("$KEY_RAIN_SHOWN_DATE_PREFIX$widgetId")
                .remove("$KEY_ZOOM_LEVEL_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_LAT_PREFIX$widgetId")
                .remove("$KEY_CURRENT_TEMP_DELTA_LON_PREFIX$widgetId")
            prefs.all.keys
                .filter { key ->
                    key.startsWith(KEY_CURRENT_TEMP_DELTA_PREFIX) ||
                        key.startsWith(KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX) ||
                        key.startsWith(KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX) ||
                        key.startsWith(KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX) ||
                        key.startsWith(KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX) ||
                        key.startsWith(KEY_CURRENT_TEMP_DELTA_LAT_PREFIX) ||
                        key.startsWith(KEY_CURRENT_TEMP_DELTA_LON_PREFIX)
                }
                .filter { key -> key.startsWith("${KEY_CURRENT_TEMP_DELTA_PREFIX}${widgetId}_") ||
                    key.startsWith("${KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX}${widgetId}_") ||
                    key.startsWith("${KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX}${widgetId}_") ||
                    key.startsWith("${KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX}${widgetId}_") ||
                    key.startsWith("${KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX}${widgetId}_") ||
                    key.startsWith("${KEY_CURRENT_TEMP_DELTA_LAT_PREFIX}${widgetId}_") ||
                    key.startsWith("${KEY_CURRENT_TEMP_DELTA_LON_PREFIX}${widgetId}_") }
                .forEach(editor::remove)
            editor.apply()
        }

        fun shouldRefreshMissingData(
            widgetId: Int,
            sourceId: String,
            refreshType: String,
            cooldownMs: Long,
        ): Boolean {
            val key = "$KEY_MISSING_DATA_REFRESH_PREFIX${widgetId}_${sourceId}_$refreshType"
            val lastRequested = prefs.getLong(key, 0L)
            return System.currentTimeMillis() - lastRequested >= cooldownMs
        }

        fun markMissingDataRefreshRequested(
            widgetId: Int,
            sourceId: String,
            refreshType: String,
        ) {
            val key = "$KEY_MISSING_DATA_REFRESH_PREFIX${widgetId}_${sourceId}_$refreshType"
            prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        }

        fun shouldRefreshMissingActuals(widgetId: Int, sourceId: String, cooldownMs: Long): Boolean =
            shouldRefreshMissingData(widgetId, sourceId, "actuals", cooldownMs)

        fun markMissingActualsRefreshRequested(widgetId: Int, sourceId: String) {
            markMissingDataRefreshRequested(widgetId, sourceId, "actuals")
        }

        /**
         * Returns true if rain summary has already been shown for this widget today.
         * Prevents noisy repeated rain messages on every widget refresh.
         */
        fun wasRainShownToday(widgetId: Int, today: String): Boolean {
            return prefs.getString("$KEY_RAIN_SHOWN_DATE_PREFIX$widgetId", null) == today
        }

        /**
         * Records that rain summary was shown for this widget today.
         */
        fun markRainShown(widgetId: Int, today: String) {
            prefs.edit().putString("$KEY_RAIN_SHOWN_DATE_PREFIX$widgetId", today).apply()
        }

        // View mode management
        fun getViewMode(widgetId: Int): ViewMode {
            val ordinal = prefs.getInt("$KEY_VIEW_MODE_PREFIX$widgetId", ViewMode.DAILY.ordinal)
            return ViewMode.entries.getOrElse(ordinal) { ViewMode.DAILY }
        }

        fun setViewMode(
            widgetId: Int,
            mode: ViewMode,
        ) {
            prefs.edit().putInt("$KEY_VIEW_MODE_PREFIX$widgetId", mode.ordinal).apply()
        }

        fun toggleViewMode(widgetId: Int): ViewMode {
            val currentMode = getViewMode(widgetId)
            val newMode =
                when (currentMode) {
                    ViewMode.DAILY -> ViewMode.TEMPERATURE
                    else -> ViewMode.DAILY // From TEMPERATURE or PRECIPITATION, go back to DAILY
                }
            setViewMode(widgetId, newMode)
            // Reset hourly offset and zoom only when entering temperature mode FROM DAILY
            if (newMode == ViewMode.TEMPERATURE && currentMode == ViewMode.DAILY) {
                setHourlyOffset(widgetId, 0)
                setZoomLevel(widgetId, ZoomLevel.WIDE)
            }
            // Reset zoom when switching back to DAILY
            if (newMode == ViewMode.DAILY) {
                setZoomLevel(widgetId, ZoomLevel.WIDE)
            }
            return newMode
        }

        fun togglePrecipitationMode(widgetId: Int): ViewMode {
            val currentMode = getViewMode(widgetId)
            val newMode = if (currentMode == ViewMode.PRECIPITATION) ViewMode.DAILY else ViewMode.PRECIPITATION
            setViewMode(widgetId, newMode)
            // Reset hourly offset and zoom only when entering precipitation mode FROM DAILY
            if (newMode == ViewMode.PRECIPITATION && currentMode == ViewMode.DAILY) {
                setHourlyOffset(widgetId, 0)
                setZoomLevel(widgetId, ZoomLevel.WIDE)
            }
            // Reset zoom when switching back to DAILY
            if (newMode == ViewMode.DAILY) {
                setZoomLevel(widgetId, ZoomLevel.WIDE)
            }
            return newMode
        }

        fun toggleCloudCoverMode(widgetId: Int): ViewMode {
            val currentMode = getViewMode(widgetId)
            val newMode = if (currentMode == ViewMode.CLOUD_COVER) ViewMode.TEMPERATURE else ViewMode.CLOUD_COVER
            setViewMode(widgetId, newMode)
            if (newMode == ViewMode.CLOUD_COVER && currentMode != ViewMode.CLOUD_COVER) {
                // Preserve hourly offset and zoom when switching between hourly graph modes
                if (currentMode == ViewMode.DAILY) {
                    setHourlyOffset(widgetId, 0)
                    setZoomLevel(widgetId, ZoomLevel.WIDE)
                }
            }
            if (newMode == ViewMode.TEMPERATURE) {
                // Keep zoom/offset when cycling back to temperature
            }
            return newMode
        }

        // Hourly offset management
        fun getHourlyOffset(widgetId: Int): Int {
            return prefs.getInt("$KEY_HOURLY_OFFSET_PREFIX$widgetId", 0)
        }

        fun setHourlyOffset(
            widgetId: Int,
            offset: Int,
        ) {
            val clampedOffset = offset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
            prefs.edit().putInt("$KEY_HOURLY_OFFSET_PREFIX$widgetId", clampedOffset).apply()
        }

        fun navigateHourlyLeft(widgetId: Int): Int {
            val currentOffset = getHourlyOffset(widgetId)
            val jump = getNavJump(widgetId)
            val newOffset = (currentOffset - jump).coerceAtLeast(MIN_HOURLY_OFFSET)
            setHourlyOffset(widgetId, newOffset)
            return newOffset
        }

        fun navigateHourlyRight(widgetId: Int): Int {
            val currentOffset = getHourlyOffset(widgetId)
            val jump = getNavJump(widgetId)
            val newOffset = (currentOffset + jump).coerceAtMost(MAX_HOURLY_OFFSET)
            setHourlyOffset(widgetId, newOffset)
            return newOffset
        }

        fun canNavigateHourlyLeft(widgetId: Int): Boolean {
            return getHourlyOffset(widgetId) > MIN_HOURLY_OFFSET
        }

        fun canNavigateHourlyRight(widgetId: Int): Boolean {
            return getHourlyOffset(widgetId) < MAX_HOURLY_OFFSET
        }

        // Zoom level management
        fun getZoomLevel(widgetId: Int): ZoomLevel {
            val ordinal = prefs.getInt("$KEY_ZOOM_LEVEL_PREFIX$widgetId", ZoomLevel.WIDE.ordinal)
            return ZoomLevel.entries.getOrElse(ordinal) { ZoomLevel.WIDE }
        }

        fun setZoomLevel(widgetId: Int, zoom: ZoomLevel) {
            prefs.edit().putInt("$KEY_ZOOM_LEVEL_PREFIX$widgetId", zoom.ordinal).apply()
        }

        fun cycleZoomLevel(widgetId: Int): ZoomLevel {
            val current = getZoomLevel(widgetId)
            val next = when (current) {
                ZoomLevel.WIDE -> ZoomLevel.NARROW
                ZoomLevel.NARROW -> ZoomLevel.WIDE
            }
            setZoomLevel(widgetId, next)
            return next
        }

        fun getNavJump(widgetId: Int): Int {
            return getZoomLevel(widgetId).navJump
        }

        /**
         * Gets the current display source for a widget based on visible sources order and toggle state.
         * Returns the WeatherSource enum.
         */
        fun getCurrentDisplaySource(widgetId: Int): WeatherSource {
            val visibleSources = getEffectiveVisibleSourcesOrder(widgetId)
            val toggleStep = getDisplaySourceToggleStep(widgetId)
            return sourceForStep(toggleStep, visibleSources)
        }

        /**
         * Sets the current display source for a widget by finding its index in the visible sources list.
         */
        fun setCurrentDisplaySource(widgetId: Int, source: WeatherSource) {
            val visibleSources = getEffectiveVisibleSourcesOrder(widgetId)
            val index = visibleSources.indexOf(source)
            if (index != -1) {
                prefs.edit().putInt("$KEY_DISPLAY_SOURCE_PREFIX$widgetId", index).apply()
            }
        }

        /**
         * Toggles the display source for a widget.
         * Returns the new source after toggling.
         */
        fun toggleDisplaySource(widgetId: Int): WeatherSource {
            val currentStep = getDisplaySourceToggleStep(widgetId)
            prefs.edit().putInt("$KEY_DISPLAY_SOURCE_PREFIX$widgetId", currentStep + 1).apply()
            return getCurrentDisplaySource(widgetId)
        }

        /**
         * Resets the toggle state for a widget (called on data refresh).
         */
        fun resetToggleState(widgetId: Int) {
            prefs.edit().remove("$KEY_DISPLAY_SOURCE_PREFIX$widgetId").apply()
        }

        /**
         * Resets toggle state for all widgets (called on data refresh).
         */
        fun resetAllToggleStates() {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(KEY_DISPLAY_SOURCE_PREFIX) }.forEach { key ->
                editor.remove(key)
            }
            editor.apply()
        }

        fun getWidgetLocation(widgetId: Int): Pair<Double, Double>? {
            val widgetPrefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
            if (!widgetPrefs.contains("${ConfigActivity.KEY_LAT_PREFIX}$widgetId") ||
                !widgetPrefs.contains("${ConfigActivity.KEY_LON_PREFIX}$widgetId")
            ) {
                val deltaLat = prefs.getString("$KEY_CURRENT_TEMP_DELTA_LAT_PREFIX$widgetId", null)?.toDoubleOrNull()
                val deltaLon = prefs.getString("$KEY_CURRENT_TEMP_DELTA_LON_PREFIX$widgetId", null)?.toDoubleOrNull()
                if (deltaLat != null && deltaLon != null) {
                    return deltaLat to deltaLon
                }

                val weatherPrefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs")
                val historicalPois = weatherPrefs.getString("historical_pois", null)
                val lastPoi = historicalPois
                    ?.split("|")
                    ?.takeLast(3)
                    ?.let { parts ->
                        if (parts.size == 3) {
                            parts[1].toDoubleOrNull()?.let { lat -> parts[2].toDoubleOrNull()?.let { lon -> lat to lon } }
                        } else {
                            null
                        }
                    }
                return lastPoi
            }
            val lat = widgetPrefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN)
            val lon = widgetPrefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", Float.NaN)
            if (lat.isNaN() || lon.isNaN()) {
                return null
            }
            return lat.toDouble() to lon.toDouble()
        }

        private fun sourceForStep(step: Int, sequence: List<WeatherSource>): WeatherSource {
            if (sequence.isEmpty()) return WeatherSource.NWS
            val normalizedStep = step.mod(sequence.size)
            return sequence[normalizedStep]
        }

        private fun getDisplaySourceToggleStep(widgetId: Int): Int {
            val key = "$KEY_DISPLAY_SOURCE_PREFIX$widgetId"
            if (prefs.contains(key)) {
                return prefs.getInt(key, 0)
            }
            // Migration fallback from old boolean state (pre-WeatherAPI rotation)
            return if (prefs.getBoolean(key, false)) 1 else 0
        }

        fun getCurrentTempDeltaState(
            widgetId: Int,
            source: WeatherSource,
        ): CurrentTemperatureDeltaState? {
            val sourceScoped = readCurrentTempDeltaState(deltaStateSuffix(widgetId, source))
            if (sourceScoped != null) {
                return sourceScoped
            }

            val legacy = readCurrentTempDeltaState(widgetId.toString()) ?: return null
            if (legacy.sourceId != source.id) {
                return null
            }

            setCurrentTempDeltaState(widgetId, source, legacy)
            clearCurrentTempDeltaState(widgetId)
            return legacy
        }

        fun setCurrentTempDeltaState(
            widgetId: Int,
            source: WeatherSource,
            state: CurrentTemperatureDeltaState,
        ) {
            val suffix = deltaStateSuffix(widgetId, source)
            prefs.edit()
                .putFloat("$KEY_CURRENT_TEMP_DELTA_PREFIX$suffix", state.delta)
                .putFloat("$KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX$suffix", state.lastObservedTemp)
                .putLong("$KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX$suffix", state.lastObservedAt)
                .putLong("$KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX$suffix", state.updatedAtMs)
                .putString("$KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX$suffix", state.sourceId)
                .putString("$KEY_CURRENT_TEMP_DELTA_LAT_PREFIX$suffix", state.locationLat.toString())
                .putString("$KEY_CURRENT_TEMP_DELTA_LON_PREFIX$suffix", state.locationLon.toString())
                .apply()
        }

        fun clearCurrentTempDeltaState(
            widgetId: Int,
            source: WeatherSource? = null,
        ) {
            val suffix = source?.let { deltaStateSuffix(widgetId, it) } ?: widgetId.toString()
            prefs.edit()
                .remove("$KEY_CURRENT_TEMP_DELTA_PREFIX$suffix")
                .remove("$KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX$suffix")
                .remove("$KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX$suffix")
                .remove("$KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX$suffix")
                .remove("$KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX$suffix")
                .remove("$KEY_CURRENT_TEMP_DELTA_LAT_PREFIX$suffix")
                .remove("$KEY_CURRENT_TEMP_DELTA_LON_PREFIX$suffix")
                .apply()
        }

        private fun deltaStateSuffix(
            widgetId: Int,
            source: WeatherSource,
        ): String = "${widgetId}_${source.id}"

        private fun readCurrentTempDeltaState(suffix: String): CurrentTemperatureDeltaState? {
            val deltaKey = "$KEY_CURRENT_TEMP_DELTA_PREFIX$suffix"
            val observedKey = "$KEY_CURRENT_TEMP_DELTA_OBSERVED_PREFIX$suffix"
            val fetchedAtKey = "$KEY_CURRENT_TEMP_DELTA_FETCHED_AT_PREFIX$suffix"
            val updatedAtKey = "$KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX$suffix"
            val sourceKey = "$KEY_CURRENT_TEMP_DELTA_SOURCE_PREFIX$suffix"
            val latKey = "$KEY_CURRENT_TEMP_DELTA_LAT_PREFIX$suffix"
            val lonKey = "$KEY_CURRENT_TEMP_DELTA_LON_PREFIX$suffix"

            if (!prefs.contains(deltaKey) || !prefs.contains(observedKey) || !prefs.contains(fetchedAtKey)) {
                return null
            }

            val sourceId = prefs.getString(sourceKey, null) ?: return null
            val lat = prefs.getString(latKey, null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString(lonKey, null)?.toDoubleOrNull() ?: return null

            return CurrentTemperatureDeltaState(
                delta = prefs.getFloat(deltaKey, 0f),
                lastObservedTemp = prefs.getFloat(observedKey, 0f),
                lastObservedAt = prefs.getLong(fetchedAtKey, 0L),
                updatedAtMs = prefs.getLong(updatedAtKey, 0L),
                sourceId = sourceId,
                locationLat = lat,
                locationLon = lon,
            )
        }
    }
