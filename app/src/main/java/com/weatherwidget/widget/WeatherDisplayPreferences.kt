package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.text.util.LocalePreferences
import com.weatherwidget.shared.settings.DisplaySettings
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

    fun personalStationDiscountPercent(
        defaultPercent: Int = DisplaySettings.PERSONAL_STATION_DISCOUNT.default,
    ): Int = DisplaySettings.PERSONAL_STATION_DISCOUNT.clamp(
        prefs.getInt(KEY_PERSONAL_STATION_DISCOUNT, defaultPercent),
    )

    fun setPersonalStationDiscountPercent(percent: Int) {
        prefs.edit().putInt(
            KEY_PERSONAL_STATION_DISCOUNT,
            DisplaySettings.PERSONAL_STATION_DISCOUNT.clamp(percent),
        ).apply()
    }

    /** Temporary experiment: [com.weatherwidget.data.model.HindcastGuard]'s max prior age (desktop: `DesktopConfig.hindcastMaxPriorAgeHours`). */
    fun hindcastMaxPriorAgeHours(): Int =
        DisplaySettings.HINDCAST_MAX_PRIOR_AGE.clamp(
            prefs.getInt(KEY_HINDCAST_MAX_PRIOR_AGE_HOURS, DisplaySettings.HINDCAST_MAX_PRIOR_AGE.default),
        )

    fun setHindcastMaxPriorAgeHours(hours: Int) {
        prefs.edit().putInt(
            KEY_HINDCAST_MAX_PRIOR_AGE_HOURS,
            DisplaySettings.HINDCAST_MAX_PRIOR_AGE.clamp(hours),
        ).apply()
    }

    /** Forward window (1..8 h, default 6) scanned for the header's rain chance in daily & hourly views. */
    fun headerRainLookaheadHours(): Int =
        DisplaySettings.HEADER_RAIN_LOOKAHEAD.clamp(
            prefs.getInt(KEY_HEADER_RAIN_LOOKAHEAD_HOURS, DisplaySettings.HEADER_RAIN_LOOKAHEAD.default),
        )

    fun setHeaderRainLookaheadHours(hours: Int) {
        prefs.edit().putInt(
            KEY_HEADER_RAIN_LOOKAHEAD_HOURS,
            DisplaySettings.HEADER_RAIN_LOOKAHEAD.clamp(hours),
        ).apply()
    }

    /** Configured span of the tight (NARROW) hourly view, in hours. Always within 4..8. */
    fun hourlyNarrowSpanHours(): Int =
        DisplaySettings.NARROW_ZOOM_SPAN.clamp(
            prefs.getInt(KEY_HOURLY_NARROW_SPAN, DisplaySettings.NARROW_ZOOM_SPAN.default),
        )

    fun setHourlyNarrowSpanHours(hours: Int) {
        prefs.edit().putInt(
            KEY_HOURLY_NARROW_SPAN,
            DisplaySettings.NARROW_ZOOM_SPAN.clamp(hours),
        ).apply()
    }

    /**
     * Whether the tap cycle includes the multi-day [com.weatherwidget.shared.graph.ZoomStage.TWO_DAY]
     * stage. Off by default: at widget width a 48h window is ~3px/hour, and the daily view already
     * covers multi-day at a glance.
     */
    fun multiDayZoomEnabled(): Boolean =
        prefs.getBoolean(KEY_MULTI_DAY_ZOOM, DisplaySettings.DEFAULT_MULTI_DAY_ZOOM_ENABLED)

    fun setMultiDayZoomEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MULTI_DAY_ZOOM, value).apply()
    }

    fun showTodayOverlayDelta(): Boolean =
        prefs.getBoolean(KEY_SHOW_TODAY_OVERLAY_DELTA, DisplaySettings.DEFAULT_TODAY_OVERLAY_DELTA)

    fun setShowTodayOverlayDelta(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TODAY_OVERLAY_DELTA, value).apply()
    }

    fun showTodayOverlayDominantTemp(): Boolean =
        prefs.getBoolean(KEY_SHOW_TODAY_OVERLAY_DOMINANT_TEMP, DisplaySettings.DEFAULT_TODAY_OVERLAY_DOMINANT_TEMP)

    fun setShowTodayOverlayDominantTemp(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TODAY_OVERLAY_DOMINANT_TEMP, value).apply()
    }

    fun showDailyZoomOutButton(): Boolean =
        prefs.getBoolean(KEY_SHOW_DAILY_ZOOM_OUT_BUTTON, DisplaySettings.DEFAULT_SHOW_DAILY_ZOOM_OUT_BUTTON)

    fun setShowDailyZoomOutButton(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_DAILY_ZOOM_OUT_BUTTON, value).apply()
    }

    /** Future daily columns draw the forecast from 24 h ago beside their own (default off). */
    fun showFuturePriorForecast(): Boolean =
        prefs.getBoolean(KEY_SHOW_FUTURE_PRIOR_FORECAST, DisplaySettings.DEFAULT_SHOW_FUTURE_PRIOR_FORECAST)

    fun setShowFuturePriorForecast(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_FUTURE_PRIOR_FORECAST, value).apply()
    }

    /** Shared cross-platform snapshot of every display setting. */
    fun snapshot(): DisplaySettings = DisplaySettings(
        narrowZoomSpanHours = hourlyNarrowSpanHours(),
        multiDayZoomEnabled = multiDayZoomEnabled(),
        personalStationDiscount = personalStationDiscountPercent(),
        hindcastMaxPriorAgeHours = hindcastMaxPriorAgeHours(),
        headerRainLookaheadHours = headerRainLookaheadHours(),
        useCelsius = useCelsius(),
        todayOverlayDelta = showTodayOverlayDelta(),
        todayOverlayDominantTemp = showTodayOverlayDominantTemp(),
        showDailyZoomOutButton = showDailyZoomOutButton(),
        showFuturePriorForecast = showFuturePriorForecast(),
    )

    /** Forecast Comparison "All sources" column sort, `DaySourceComparison.Sort.encode()` text; null = default. */
    fun sourceCompareSort(): String? = prefs.getString(KEY_SOURCE_COMPARE_SORT, null)

    fun setSourceCompareSort(value: String) {
        prefs.edit().putString(KEY_SOURCE_COMPARE_SORT, value).apply()
    }

    private companion object {
        const val TAG = "UNIT_DEFAULT"
        const val KEY_USE_CELSIUS = "use_celsius"
        const val KEY_PERSONAL_STATION_DISCOUNT = "personal_station_discount"
        const val KEY_HINDCAST_MAX_PRIOR_AGE_HOURS = "hindcast_max_prior_age_hours"
        const val KEY_HEADER_RAIN_LOOKAHEAD_HOURS = "header_rain_lookahead_hours"
        const val KEY_HOURLY_NARROW_SPAN = "hourly_narrow_span_hours"
        const val KEY_MULTI_DAY_ZOOM = "hourly_multi_day_zoom_enabled"
        const val KEY_SHOW_TODAY_OVERLAY_DELTA = "show_today_overlay_delta"
        const val KEY_SHOW_TODAY_OVERLAY_DOMINANT_TEMP = "show_today_overlay_dominant_temp"
        const val KEY_SHOW_DAILY_ZOOM_OUT_BUTTON = "show_daily_zoom_out_button"
        const val KEY_SHOW_FUTURE_PRIOR_FORECAST = "show_future_prior_forecast"
        // Desktop: DesktopConfig.sourceCompareSort.
        const val KEY_SOURCE_COMPARE_SORT = "source_compare_sort"
    }
}
