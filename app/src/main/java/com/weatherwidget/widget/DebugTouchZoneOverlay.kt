package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.BuildConfig
import com.weatherwidget.R
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Debug-only overlay that tints widget touch zones with semi-transparent colors so tap-target
 * alignment can be verified visually on a device or emulator.
 *
 * Driven via `adb` through `com.weatherwidget.debug.DebugTouchZoneReceiver` (which lives in
 * `src/debug` and requires `android.permission.DUMP`). Every entry point is also gated on
 * [BuildConfig.DEBUG], so release builds never read prefs or mutate [RemoteViews].
 */
object DebugTouchZoneOverlay {
    private const val PREFS_NAME = "weather_prefs"
    private const val KEY_ENABLED = "debug_show_touch_zones"

    @Volatile
    private var cachedEnabled: Boolean = false

    @Volatile
    private var clearOnNextPush: Boolean = false

    /**
     * Touch-zone view IDs paired with distinct semi-transparent tint colors:
     * - API toggle: green
     * - Settings gear: red
     * - Current temp / weather icon: blue
     * - Rain %: cyan
     * - Center & inline header buttons: amber / purple
     * - Side nav zones: faint white
     */
    internal val ZONE_COLORS: List<Pair<Int, Int>> = listOf(
        R.id.api_touch_zone to 0x6600E676,
        R.id.text_mode_api_touch_zone to 0x6600E676,
        R.id.settings_touch_zone to 0x66FF1744,
        R.id.text_mode_settings_touch_zone to 0x66FF1744,
        R.id.current_temp_zone to 0x552979FF,
        R.id.precip_touch_zone to 0x5500E5FF,
        R.id.home_touch_zone to 0x55FFAB00,
        R.id.weather_stations_touch_zone to 0x55D500F9,
        R.id.graph_selector_touch_zone to 0x55FFAB00,
        R.id.months_view_touch_zone to 0x55D500F9,
        R.id.forecast_comparison_activity_touch_zone to 0x55FFAB00,
        R.id.home_touch_zone_inline to 0x55FFAB00,
        R.id.weather_stations_touch_zone_inline to 0x55D500F9,
        R.id.graph_selector_touch_zone_inline to 0x55FFAB00,
        R.id.months_view_touch_zone_inline to 0x55D500F9,
        R.id.forecast_comparison_activity_touch_zone_inline to 0x55FFAB00,
        R.id.nav_left_zone to 0x26FFFFFF,
        R.id.nav_right_zone to 0x26FFFFFF,
    )

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        cachedEnabled = SharedPreferencesUtil.getPrefs(context, PREFS_NAME).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(): Boolean = BuildConfig.DEBUG && cachedEnabled

    fun set(context: Context, enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        val wasEnabled = cachedEnabled
        cachedEnabled = enabled
        if (wasEnabled && !enabled) {
            clearOnNextPush = true
        }
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
    }

    /**
     * Apply touch-zone tints to [views] when enabled, or explicitly clear them to transparent if
     * the overlay was just disabled (so even a partial push removes previous tints).
     */
    fun apply(views: RemoteViews) {
        if (!BuildConfig.DEBUG) return
        when {
            cachedEnabled -> {
                for ((viewId, color) in ZONE_COLORS) {
                    views.setInt(viewId, "setBackgroundColor", color)
                }
            }
            clearOnNextPush -> {
                for ((viewId, _) in ZONE_COLORS) {
                    views.setInt(viewId, "setBackgroundColor", Color.TRANSPARENT)
                }
            }
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        cachedEnabled = false
        clearOnNextPush = false
    }
}
