package com.weatherwidget.util

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import com.weatherwidget.R

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

    /** Returns the cloud ratio (0.0 = clear, 1.0 = overcast) for mixed-condition icons, or null for non-mixed. */
    fun cloudRatio(iconRes: Int): Float? {
        return when (iconRes) {
            R.drawable.ic_weather_fog_sunny -> 0.15f
            R.drawable.ic_weather_partly_cloudy,
            R.drawable.ic_weather_partly_cloudy_night -> 0.35f
            R.drawable.ic_weather_partly_cloudy_chance_rain -> 0.40f
            R.drawable.ic_weather_mostly_cloudy,
            R.drawable.ic_weather_mostly_cloudy_night,
            R.drawable.ic_weather_fog_cloudy -> 0.70f
            else -> null
        }
    }

    /** Returns a vertical LinearGradient for a mixed-condition bar (gold top → gray/blue bottom), or null for solid-color bars. */
    fun forecastBarGradient(iconRes: Int, topY: Float, bottomY: Float): LinearGradient? {
        val ratio = cloudRatio(iconRes) ?: return null
        val topColor = FORECAST_SUNNY
        val bottomColor = if (iconRes == R.drawable.ic_weather_partly_cloudy_chance_rain) FORECAST_RAINY else FORECAST_CLOUDY
        return LinearGradient(
            0f, topY, 0f, bottomY,
            intArrayOf(topColor, topColor, bottomColor),
            floatArrayOf(0f, (1f - ratio).coerceIn(0.01f, 0.99f), 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
