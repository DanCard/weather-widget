package com.weatherwidget.shared.util

/**
 * Shared color constants and decision logic for weather-adaptive forecast rendering.
 * Platform-specific gradient/shader code stays in Android/desktop consuming modules.
 *
 * Colors are stored as ARGB hex ints (0xAARRGGBB).
 */
object WeatherColors {
    const val FORECAST_SUNNY: Int = 0xFFF4C542.toInt()   // Amber/gold
    const val FORECAST_CLOUDY: Int = 0xFF8E99A4.toInt()  // Slate gray
    const val FORECAST_RAINY: Int = 0xFF5A8FBF.toInt()   // Steel blue
    const val FORECAST_NIGHT: Int = 0xFFBBBBBB.toInt()   // Muted silver
    const val FORECAST_TWILIGHT: Int = 0xFFFFA726.toInt() // Warm amber
    const val OBSERVED: Int = 0xFFFF5588.toInt()          // Bright rose-pink

    /**
     * Maps weather condition flags to a forecast color.
     * Priority: rainy > night > twilight+sunny > mixed > sunny > cloudy.
     */
    fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean, isTwilight: Boolean = false): Int {
        return when {
            isRainy -> FORECAST_RAINY
            isNight -> FORECAST_NIGHT
            isTwilight && isSunny -> FORECAST_TWILIGHT
            isMixed -> FORECAST_SUNNY
            isSunny -> FORECAST_SUNNY
            else -> FORECAST_CLOUDY
        }
    }
}
