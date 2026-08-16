package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.text.util.LocalePreferences
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.util.UnitDefaults

/** Owns app-wide display-unit and observation-weight preferences. */
internal class WeatherDisplayPreferences(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    fun useCelsius(): Boolean {
        if (prefs.contains(KEY_USE_CELSIUS)) {
            return prefs.getBoolean(KEY_USE_CELSIUS, false)
        }

        val region = android.content.res.Resources.getSystem().configuration.locales[0].country
        val appLocale = context.resources.configuration.locales[0]
        val osTemperatureUnit = LocalePreferences.getTemperatureUnit(appLocale, false)
        val default = UnitDefaults.defaultUseCelsius(osTemperatureUnit, region)
        Log.d(
            TAG,
            "useCelsius default: osUnit='$osTemperatureUnit' region=$region " +
                "appLocale=${appLocale.toLanguageTag()} -> celsius=$default",
        )
        return default
    }

    fun setUseCelsius(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CELSIUS, value).apply()
    }

    fun personalStationDiscountPercent(defaultPercent: Int): Int =
        prefs.getInt(KEY_PERSONAL_STATION_DISCOUNT, defaultPercent)

    fun setPersonalStationDiscountPercent(percent: Int) {
        prefs.edit().putInt(KEY_PERSONAL_STATION_DISCOUNT, percent.coerceIn(0, 100)).apply()
    }

    /** Configured span of the tight (NARROW) hourly view, in hours. Always within 4..8. */
    fun hourlyNarrowSpanHours(): Int =
        HourlyZoomRules.clampNarrowSpan(
            prefs.getInt(KEY_HOURLY_NARROW_SPAN, HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS),
        )

    fun setHourlyNarrowSpanHours(hours: Int) {
        prefs.edit().putInt(KEY_HOURLY_NARROW_SPAN, HourlyZoomRules.clampNarrowSpan(hours)).apply()
    }

    /**
     * Whether the tap cycle includes the multi-day [com.weatherwidget.shared.graph.ZoomStage.TWO_DAY]
     * stage. Off by default: at widget width a 48h window is ~3px/hour, and the daily view already
     * covers multi-day at a glance.
     */
    fun multiDayZoomEnabled(): Boolean = prefs.getBoolean(KEY_MULTI_DAY_ZOOM, false)

    fun setMultiDayZoomEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MULTI_DAY_ZOOM, value).apply()
    }

    fun showTodayOverlayDelta(): Boolean = prefs.getBoolean(KEY_SHOW_TODAY_OVERLAY_DELTA, false)

    fun setShowTodayOverlayDelta(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TODAY_OVERLAY_DELTA, value).apply()
    }

    fun showTodayOverlayDominantTemp(): Boolean =
        prefs.getBoolean(KEY_SHOW_TODAY_OVERLAY_DOMINANT_TEMP, false)

    fun setShowTodayOverlayDominantTemp(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TODAY_OVERLAY_DOMINANT_TEMP, value).apply()
    }

    fun showTodayOverlayDominantAge(): Boolean =
        prefs.getBoolean(KEY_SHOW_TODAY_OVERLAY_DOMINANT_AGE, false)

    fun setShowTodayOverlayDominantAge(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TODAY_OVERLAY_DOMINANT_AGE, value).apply()
    }

    private companion object {
        const val TAG = "UNIT_DEFAULT"
        const val KEY_USE_CELSIUS = "use_celsius"
        const val KEY_PERSONAL_STATION_DISCOUNT = "personal_station_discount"
        const val KEY_HOURLY_NARROW_SPAN = "hourly_narrow_span_hours"
        const val KEY_MULTI_DAY_ZOOM = "hourly_multi_day_zoom_enabled"
        const val KEY_SHOW_TODAY_OVERLAY_DELTA = "show_today_overlay_delta"
        const val KEY_SHOW_TODAY_OVERLAY_DOMINANT_TEMP = "show_today_overlay_dominant_temp"
        const val KEY_SHOW_TODAY_OVERLAY_DOMINANT_AGE = "show_today_overlay_dominant_age"
    }
}
