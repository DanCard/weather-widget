package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

enum class AccuracyDisplayMode {
    NONE,           // Don't show forecast comparison
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

        const val MIN_DATE_OFFSET = -30  // 30 days back
        const val MAX_DATE_OFFSET = 14   // 14 days forward
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
        val ordinal = prefs.getInt(KEY_ACCURACY_DISPLAY, AccuracyDisplayMode.ACCURACY_DOT.ordinal)
        return AccuracyDisplayMode.entries.getOrElse(ordinal) { AccuracyDisplayMode.ACCURACY_DOT }
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
        prefs.edit().remove("$KEY_DATE_OFFSET_PREFIX$widgetId").apply()
    }
}
