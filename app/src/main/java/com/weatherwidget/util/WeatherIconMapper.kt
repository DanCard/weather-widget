package com.weatherwidget.util

import com.weatherwidget.R

object WeatherIconMapper {

    fun getIconResource(condition: String?, isNight: Boolean = false): Int {
        if (condition == null) return R.drawable.ic_weather_unknown
        
        val lowerCondition = condition.lowercase()
        return when {
            lowerCondition.contains("thunder") || lowerCondition.contains("storm") -> R.drawable.ic_weather_storm
            lowerCondition.contains("snow") || lowerCondition.contains("flurries") || lowerCondition.contains("blizzard") -> R.drawable.ic_weather_snow
            lowerCondition.contains("rain") || lowerCondition.contains("drizzle") || lowerCondition.contains("shower") -> R.drawable.ic_weather_rain
            lowerCondition.contains("fog") || lowerCondition.contains("mist") || lowerCondition.contains("haze") -> R.drawable.ic_weather_fog
            lowerCondition.contains("(75%)") || lowerCondition.contains("mostly cloudy") -> R.drawable.ic_weather_mostly_cloudy
            lowerCondition.contains("partly") && lowerCondition.contains("cloudy") -> {
                if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
            }
            lowerCondition.contains("(25%)") || lowerCondition.contains("mostly clear") || lowerCondition.contains("mostly sunny") -> {
                if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
            }
            lowerCondition.contains("cloudy") || lowerCondition.contains("overcast") -> R.drawable.ic_weather_cloudy
            lowerCondition.contains("wind") || lowerCondition.contains("breez") || lowerCondition.contains("gale") -> R.drawable.ic_weather_wind
            lowerCondition.contains("clear") || lowerCondition.contains("sunny") || lowerCondition.contains("fair") || lowerCondition.contains("observed") -> {
                if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_clear
            }
            else -> R.drawable.ic_weather_clear // Optimistic fallback: default to CLEAR instead of CLOUDY
        }
    }
}
