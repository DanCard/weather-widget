package com.weatherwidget.util

import android.graphics.Color

/**
 * Shared color mapping for weather-adaptive forecast rendering.
 * Forecast colors vary by weather condition; actual/observed uses a fixed hot pink.
 */
object WeatherConditionColors {
    val FORECAST_SUNNY = Color.parseColor("#F4C542")    // Amber/gold
    val FORECAST_CLOUDY = Color.parseColor("#8E99A4")   // Slate gray
    val FORECAST_RAINY = Color.parseColor("#5A8FBF")    // Steel blue
    val FORECAST_NIGHT = Color.parseColor("#BBBBBB")    // Muted silver
    val OBSERVED = Color.parseColor("#FF3366")           // Hot pink

    /** Maps weather condition flags to a forecast color. Priority: rainy > cloudy/mixed > night > sunny. */
    fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean): Int {
        return when {
            isRainy -> FORECAST_RAINY
            isMixed -> FORECAST_CLOUDY
            isNight -> FORECAST_NIGHT
            isSunny -> FORECAST_SUNNY
            else -> FORECAST_SUNNY  // Default: clear
        }
    }
}
