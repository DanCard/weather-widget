package com.weatherwidget.util

import com.weatherwidget.shared.util.WeatherColors

/**
 * Android names for the forecast colours. Colours, the forecast-colour decision and the daily bar's
 * sky are all single-sourced in `:shared` ([WeatherColors], `AdaptiveBarStrokes`).
 */
object WeatherConditionColors {
    // Re-export the shared ARGB constants so existing callers/tests keep their names.
    val FORECAST_SUNNY = WeatherColors.FORECAST_SUNNY   // Amber/gold
    val FORECAST_CLOUDY = WeatherColors.FORECAST_CLOUDY // Slate gray
    val FORECAST_RAINY = WeatherColors.FORECAST_RAINY   // Steel blue
    val FORECAST_NIGHT = WeatherColors.FORECAST_NIGHT   // Muted silver
    val FORECAST_TWILIGHT = WeatherColors.FORECAST_TWILIGHT // Warm amber for sunrise/sunset hours
    val OBSERVED = WeatherColors.OBSERVED               // Bright rose pink

    /** Maps weather condition flags to a forecast color. Priority: rainy > night > twilight+sunny > mixed > sunny > cloudy. */
    fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean, isTwilight: Boolean = false): Int =
        WeatherColors.forecastColor(isSunny, isRainy, isMixed, isNight, isTwilight)
}
