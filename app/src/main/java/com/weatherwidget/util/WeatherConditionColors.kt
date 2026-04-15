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
    val FORECAST_TWILIGHT = Color.parseColor("#FFA726") // Warm amber for sunrise/sunset hours
    val OBSERVED = Color.parseColor("#FF3366")           // Hot pink

    /** Maps weather condition flags to a forecast color. Priority: rainy > night > twilight+sunny > mixed > sunny > cloudy. */
    fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean, isTwilight: Boolean = false): Int {
        return when {
            isRainy -> FORECAST_RAINY
            isNight -> FORECAST_NIGHT
            isTwilight && isSunny -> FORECAST_TWILIGHT
            isMixed -> FORECAST_SUNNY
            isSunny -> FORECAST_SUNNY
            else -> FORECAST_CLOUDY  // Default for cloudy/foggy/windy
        }
    }

    private val CHANCE_RAIN_ICONS = setOf(
        R.drawable.ic_weather_partly_cloudy_chance_rain,
        R.drawable.ic_weather_partly_cloudy_chance_rain_night,
        R.drawable.ic_weather_clear_chance_rain,
        R.drawable.ic_weather_night_chance_rain,
        R.drawable.ic_weather_cloudy_chance_rain,
    )

    /** Returns the cloud ratio (0.0 = clear, 1.0 = overcast) for mixed-condition icons, or null for non-mixed. */
    fun cloudRatio(iconRes: Int): Float? {
        return when (iconRes) {
            R.drawable.ic_weather_horizon_sun -> 0.12f
            R.drawable.ic_weather_fog_sunny -> 0.15f
            R.drawable.ic_weather_clear_slight_chance_rain -> 0.22f
            R.drawable.ic_weather_night_slight_chance_rain -> 0.22f
            R.drawable.ic_weather_clear_chance_rain -> 0.25f
            R.drawable.ic_weather_night_chance_rain -> 0.25f
            R.drawable.ic_weather_fog_light,
            R.drawable.ic_weather_fog_light_night -> 0.30f
            R.drawable.ic_weather_partly_cloudy,
            R.drawable.ic_weather_partly_cloudy_night -> 0.35f
            R.drawable.ic_weather_partly_cloudy_slight_chance_rain,
            R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night -> 0.38f
            R.drawable.ic_weather_partly_cloudy_chance_rain,
            R.drawable.ic_weather_partly_cloudy_chance_rain_night -> 0.40f
            R.drawable.ic_weather_fog_night -> 0.50f
            R.drawable.ic_weather_cloudy_slight_chance_rain -> 0.60f
            R.drawable.ic_weather_cloudy_chance_rain -> 0.65f
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
        val bottomColor = if (iconRes in CHANCE_RAIN_ICONS) FORECAST_RAINY else FORECAST_CLOUDY
        return LinearGradient(
            0f, topY, 0f, bottomY,
            intArrayOf(topColor, topColor, bottomColor),
            floatArrayOf(0f, (1f - ratio).coerceIn(0.01f, 0.99f), 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
