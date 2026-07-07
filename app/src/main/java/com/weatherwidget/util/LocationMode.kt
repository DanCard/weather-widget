package com.weatherwidget.util

import android.content.Context

/**
 * Single global flag deciding whether widget locations follow the device or stay pinned.
 *
 * - [FOLLOW_DEVICE]: the GPS auto-heal ([com.weatherwidget.widget.GpsResampler]) keeps every
 *   widget's location in sync with the device's cached fused location.
 * - [FIXED]: the user deliberately chose a location (search result or manual coordinates);
 *   both heal paths skip entirely so the choice is never clobbered.
 *
 * Absent key = [FOLLOW_DEVICE], so installs from before this flag existed keep healing.
 * Stored in `weather_prefs` (not per-widget: the heal already applies to all widgets at once).
 */
object LocationMode {
    const val FOLLOW_DEVICE = "follow_device"
    const val FIXED = "fixed"

    private const val PREFS_NAME = "weather_prefs"
    private const val KEY = "location_mode"

    fun get(context: Context): String =
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).getString(KEY, FOLLOW_DEVICE)
            ?: FOLLOW_DEVICE

    fun set(context: Context, mode: String) {
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit().putString(KEY, mode).apply()
    }
}
