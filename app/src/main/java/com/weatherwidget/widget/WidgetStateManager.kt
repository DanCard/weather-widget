package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.model.WeatherSource
import javax.inject.Inject
import javax.inject.Singleton

enum class AccuracyDisplayMode {
    NONE, // Don't show forecast comparison
    FORECAST_BAR, // Yellow bar showing forecast alongside actual (Recommended)
    ACCURACY_DOT, // Colored dot (green/yellow/red) based on accuracy
    SIDE_BY_SIDE, // Show "72° (F:68°)"
    DIFFERENCE, // Show "72° (+4)"
}

enum class ApiPreference {
    ALTERNATE, // Pseudo-random initial source, changes daily
    PREFER_NWS, // Default to NWS, toggle switches to Open-Meteo
    PREFER_OPENMETEO, // Default to Open-Meteo, toggle switches to NWS
}

enum class ViewMode {
    DAILY, // Default: shows daily forecast bars
    HOURLY, // Alternative: shows hourly temperature curve
    PRECIPITATION, // Hourly precipitation probability graph
}

@Singleton
class WidgetStateManager
    @Inject
    constructor(
        private val context: Context,
    ) {
        companion object {
            private const val PREFS_NAME = "widget_state_prefs"
            private const val KEY_DATE_OFFSET_PREFIX = "widget_date_offset_"
            private const val KEY_ACCURACY_DISPLAY = "accuracy_display_mode"
            private const val KEY_API_PREFERENCE = "api_preference"
            private const val KEY_DISPLAY_SOURCE_PREFIX = "widget_display_source_"
            private const val KEY_VIEW_MODE_PREFIX = "widget_view_mode_"
            private const val KEY_HOURLY_OFFSET_PREFIX = "widget_hourly_offset_"

            const val MIN_DATE_OFFSET = -30 // Last 30 days of history
            const val MAX_DATE_OFFSET = 14 // 14 days forward
            const val MIN_HOURLY_OFFSET = -8 // Allow scrolling to see 16h of history (8h default + 8h scroll)
            const val MAX_HOURLY_OFFSET = 96 // Allow navigating up to 4 days into the future
            const val HOURLY_NAV_JUMP = 6 // Navigate in 6-hour chunks

            @Deprecated("Use WeatherSource.NWS.displayName instead", ReplaceWith("WeatherSource.NWS.displayName"))
            const val SOURCE_NWS = "NWS"

            @Deprecated("Use WeatherSource.OPEN_METEO.displayName instead", ReplaceWith("WeatherSource.OPEN_METEO.displayName"))
            const val SOURCE_OPEN_METEO = "Open-Meteo"

            @Deprecated("Use WeatherSource.GENERIC_GAP.id instead", ReplaceWith("WeatherSource.GENERIC_GAP.id"))
            const val SOURCE_GENERIC_GAP = "Generic"
        }

        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        fun getAccuracyDisplayMode(): AccuracyDisplayMode {
            val ordinal = prefs.getInt(KEY_ACCURACY_DISPLAY, AccuracyDisplayMode.FORECAST_BAR.ordinal)
            return AccuracyDisplayMode.entries.getOrElse(ordinal) { AccuracyDisplayMode.FORECAST_BAR }
        }

        fun setAccuracyDisplayMode(mode: AccuracyDisplayMode) {
            prefs.edit().putInt(KEY_ACCURACY_DISPLAY, mode.ordinal).apply()
        }

        fun getApiPreference(): ApiPreference {
            val ordinal = prefs.getInt(KEY_API_PREFERENCE, ApiPreference.PREFER_NWS.ordinal)
            return ApiPreference.entries.getOrElse(ordinal) { ApiPreference.PREFER_NWS }
        }

        fun setApiPreference(preference: ApiPreference) {
            prefs.edit().putInt(KEY_API_PREFERENCE, preference.ordinal).apply()
        }

        fun clearWidgetState(widgetId: Int) {
            prefs.edit()
                .remove("$KEY_DATE_OFFSET_PREFIX$widgetId")
                .remove("$KEY_DISPLAY_SOURCE_PREFIX$widgetId")
                .remove("$KEY_VIEW_MODE_PREFIX$widgetId")
                .remove("$KEY_HOURLY_OFFSET_PREFIX$widgetId")
                .apply()
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
                    ViewMode.DAILY -> ViewMode.HOURLY
                    else -> ViewMode.DAILY // From HOURLY or PRECIPITATION, go back to DAILY
                }
            setViewMode(widgetId, newMode)
            // Reset hourly offset when entering hourly mode
            if (newMode == ViewMode.HOURLY) {
                setHourlyOffset(widgetId, 0)
            }
            return newMode
        }

        fun togglePrecipitationMode(widgetId: Int): ViewMode {
            val currentMode = getViewMode(widgetId)
            val newMode = if (currentMode == ViewMode.PRECIPITATION) ViewMode.DAILY else ViewMode.PRECIPITATION
            setViewMode(widgetId, newMode)
            if (newMode == ViewMode.PRECIPITATION) {
                setHourlyOffset(widgetId, 0)
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
            val newOffset = (currentOffset - HOURLY_NAV_JUMP).coerceAtLeast(MIN_HOURLY_OFFSET)
            setHourlyOffset(widgetId, newOffset)
            return newOffset
        }

        fun navigateHourlyRight(widgetId: Int): Int {
            val currentOffset = getHourlyOffset(widgetId)
            val newOffset = (currentOffset + HOURLY_NAV_JUMP).coerceAtMost(MAX_HOURLY_OFFSET)
            setHourlyOffset(widgetId, newOffset)
            return newOffset
        }

        fun canNavigateHourlyLeft(widgetId: Int): Boolean {
            return getHourlyOffset(widgetId) > MIN_HOURLY_OFFSET
        }

        fun canNavigateHourlyRight(widgetId: Int): Boolean {
            return getHourlyOffset(widgetId) < MAX_HOURLY_OFFSET
        }

        /**
         * Gets the current display source for a widget based on preference and toggle state.
         * Returns the WeatherSource enum.
         */
        fun getCurrentDisplaySource(widgetId: Int): WeatherSource {
            val preference = getApiPreference()
            val isToggled = prefs.getBoolean("$KEY_DISPLAY_SOURCE_PREFIX$widgetId", false)

            return when (preference) {
                ApiPreference.PREFER_NWS -> if (isToggled) WeatherSource.OPEN_METEO else WeatherSource.NWS
                ApiPreference.PREFER_OPENMETEO -> if (isToggled) WeatherSource.NWS else WeatherSource.OPEN_METEO
                ApiPreference.ALTERNATE -> {
                    // Pseudo-random initial source: changes daily, varies by widget
                    val daysSinceEpoch = (System.currentTimeMillis() / (1000 * 60 * 60 * 24)).toInt()
                    val defaultSource = if ((daysSinceEpoch + widgetId) % 2 == 0) WeatherSource.NWS else WeatherSource.OPEN_METEO
                    if (isToggled) {
                        if (defaultSource == WeatherSource.NWS) WeatherSource.OPEN_METEO else WeatherSource.NWS
                    } else {
                        defaultSource
                    }
                }
            }
        }

        /**
         * Toggles the display source for a widget.
         * Returns the new source after toggling.
         */
        fun toggleDisplaySource(widgetId: Int): WeatherSource {
            val isToggled = prefs.getBoolean("$KEY_DISPLAY_SOURCE_PREFIX$widgetId", false)
            prefs.edit().putBoolean("$KEY_DISPLAY_SOURCE_PREFIX$widgetId", !isToggled).apply()
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
    }
