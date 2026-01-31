package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

enum class AccuracyDisplayMode {
    NONE,           // Don't show forecast comparison
    FORECAST_BAR,   // Yellow bar showing forecast alongside actual (Recommended)
    ACCURACY_DOT,   // Colored dot (green/yellow/red) based on accuracy
    SIDE_BY_SIDE,   // Show "72° (F:68°)"
    DIFFERENCE      // Show "72° (+4)"
}

enum class ApiPreference {
    ALTERNATE,      // Alternate between NWS and Open-Meteo
    PREFER_NWS,     // Prefer NWS, fallback to Open-Meteo
    PREFER_OPENMETEO // Prefer Open-Meteo, fallback to NWS
}

@Singleton
class WidgetStateManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "widget_state_prefs"
        private const val KEY_DATE_OFFSET_PREFIX = "widget_date_offset_"
        private const val KEY_ACCURACY_DISPLAY = "accuracy_display_mode"
        private const val KEY_API_PREFERENCE = "api_preference"
        private const val KEY_DISPLAY_SOURCE_PREFIX = "widget_display_source_"

        const val MIN_DATE_OFFSET = -7   // Last 7 days of history
        const val MAX_DATE_OFFSET = 14   // 14 days forward

        const val SOURCE_NWS = "NWS"
        const val SOURCE_OPEN_METEO = "Open-Meteo"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDateOffset(widgetId: Int): Int {
        return prefs.getInt("$KEY_DATE_OFFSET_PREFIX$widgetId", 0)
    }

    fun setDateOffset(widgetId: Int, offset: Int) {
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
        val ordinal = prefs.getInt(KEY_API_PREFERENCE, ApiPreference.ALTERNATE.ordinal)
        return ApiPreference.entries.getOrElse(ordinal) { ApiPreference.ALTERNATE }
    }

    fun setApiPreference(preference: ApiPreference) {
        prefs.edit().putInt(KEY_API_PREFERENCE, preference.ordinal).apply()
    }

    fun clearWidgetState(widgetId: Int) {
        prefs.edit()
            .remove("$KEY_DATE_OFFSET_PREFIX$widgetId")
            .remove("$KEY_DISPLAY_SOURCE_PREFIX$widgetId")
            .apply()
    }

    /**
     * Gets the current display source for a widget based on preference and toggle state.
     * Returns the source string to query ("NWS" or "Open-Meteo").
     */
    fun getCurrentDisplaySource(widgetId: Int): String {
        val preference = getApiPreference()
        val isToggled = prefs.getBoolean("$KEY_DISPLAY_SOURCE_PREFIX$widgetId", false)

        return when (preference) {
            ApiPreference.PREFER_NWS -> if (isToggled) SOURCE_OPEN_METEO else SOURCE_NWS
            ApiPreference.PREFER_OPENMETEO -> if (isToggled) SOURCE_NWS else SOURCE_OPEN_METEO
            ApiPreference.ALTERNATE -> {
                // For alternate mode, default to NWS first, toggle switches to Open-Meteo
                if (isToggled) SOURCE_OPEN_METEO else SOURCE_NWS
            }
        }
    }

    /**
     * Toggles the display source for a widget.
     * Returns the new source after toggling.
     */
    fun toggleDisplaySource(widgetId: Int): String {
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
