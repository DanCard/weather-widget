package com.weatherwidget.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * Maps weather conditions to desktop resources.
 * These resources were ported from the Android widget's XML drawables.
 */
object WeatherIcon {
    fun getIconResource(condition: String?): String {
        if (condition == null) return "drawable/ic_weather_unknown.xml"
        val lower = condition.lowercase()
        return when {
            lower.contains("storm") || lower.contains("thunder") || lower.contains("hail") -> "drawable/ic_weather_storm.xml"
            lower.contains("snow") || lower.contains("flurries") || lower.contains("blizzard") || lower.contains("sleet") || lower.contains("ice pellet") -> "drawable/ic_weather_snow.xml"
            lower.contains("rain") || lower.contains("drizzle") || lower.contains("shower") -> "drawable/ic_weather_rain.xml"
            lower.contains("dense fog") -> "drawable/ic_weather_fog_dense.xml"
            lower.contains("patchy fog") || lower.contains("light fog") -> "drawable/ic_weather_fog_light.xml"
            lower.contains("fog") || lower.contains("mist") || lower.contains("haze") -> "drawable/ic_weather_fog.xml"
            lower.contains("mostly cloudy") || lower.contains("(75%)") || lower.contains("broken") -> "drawable/ic_weather_mostly_cloudy.xml"
            lower.contains("partly") -> "drawable/ic_weather_partly_cloudy.xml"
            lower.contains("mostly clear") || lower.contains("mostly sunny") || lower.contains("partly sunny") || lower.contains("(25%)") -> "drawable/ic_weather_mostly_clear.xml"
            lower.contains("cloudy") || lower.contains("overcast") -> "drawable/ic_weather_cloudy.xml"
            lower.contains("clear") || lower.contains("sunny") || lower.contains("fair") -> "drawable/ic_weather_clear.xml"
            else -> "drawable/ic_weather_clear.xml"
        }
    }

    @Composable
    fun painter(condition: String?): Painter {
        return painterResource(getIconResource(condition))
    }
}
