package com.weatherwidget.util

import com.weatherwidget.R

object WeatherIconMapper {
    fun getIconResource(
        condition: String?,
        isNight: Boolean = false,
    ): Int {
        if (condition == null) return R.drawable.ic_weather_unknown

        val lowerCondition = condition.lowercase()
        val normalizedCondition = normalizePatchyFogTransitionCondition(lowerCondition)
        val isSlightChance = normalizedCondition.contains("slight chance") || normalizedCondition.contains("patchy")
        
        return when {
            normalizedCondition.contains("thunder") || normalizedCondition.contains("storm") -> {
                if (isSlightChance) {
                    if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
                } else R.drawable.ic_weather_storm
            }
            normalizedCondition.contains("snow") || normalizedCondition.contains("flurries") || normalizedCondition.contains("blizzard") -> {
                if (isSlightChance) {
                    if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
                } else R.drawable.ic_weather_snow
            }
            normalizedCondition.contains("rain") || normalizedCondition.contains("drizzle") || normalizedCondition.contains("shower") -> {
                if (isSlightChance) {
                    if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
                } else R.drawable.ic_weather_rain
            }
            normalizedCondition.contains("fog") && (normalizedCondition.contains("sunny") || normalizedCondition.contains("clear")) -> R.drawable.ic_weather_fog_sunny
            normalizedCondition.contains("fog") && (normalizedCondition.contains("cloudy") || normalizedCondition.contains("overcast")) -> R.drawable.ic_weather_fog_cloudy
            normalizedCondition.contains(
                "fog",
            ) || normalizedCondition.contains("mist") || normalizedCondition.contains("haze") -> R.drawable.ic_weather_fog
            normalizedCondition.contains("(75%)") || normalizedCondition.contains("mostly cloudy") -> {
                if (isNight) R.drawable.ic_weather_mostly_cloudy_night else R.drawable.ic_weather_partly_cloudy
            }
            normalizedCondition.contains("broken") -> {
                if (isNight) R.drawable.ic_weather_mostly_cloudy_night else R.drawable.ic_weather_mostly_cloudy
            }
            normalizedCondition.contains("(25%)") || normalizedCondition.contains("mostly clear") || normalizedCondition.contains("mostly sunny") || normalizedCondition.contains("partly sunny") -> {
                if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
            }
            normalizedCondition.contains("partly") -> {
                if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
            }
            normalizedCondition.contains("cloudy") || normalizedCondition.contains("overcast") -> R.drawable.ic_weather_cloudy
            normalizedCondition.contains(
                "wind",
            ) || normalizedCondition.contains("breez") || normalizedCondition.contains("gale") -> R.drawable.ic_weather_wind
            normalizedCondition.contains(
                "clear",
            ) || normalizedCondition.contains("sunny") || normalizedCondition.contains("fair") || normalizedCondition.contains("observed") -> {
                if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_clear
            }
            else -> R.drawable.ic_weather_clear // Optimistic fallback: default to CLEAR instead of CLOUDY
        }
    }

    private fun normalizePatchyFogTransitionCondition(condition: String): String {
        if (!condition.contains("patchy fog")) return condition
        val thenIndex = condition.indexOf(" then ")
        if (thenIndex == -1) return condition
        return condition.substring(thenIndex + " then ".length).trim()
    }

    fun isSunny(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_clear ||
               iconRes == R.drawable.ic_weather_mostly_clear ||
               iconRes == R.drawable.ic_weather_night
    }

    fun isRainy(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_rain ||
               iconRes == R.drawable.ic_weather_storm ||
               iconRes == R.drawable.ic_weather_snow
    }

    fun isMixed(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_mostly_cloudy ||
               iconRes == R.drawable.ic_weather_mostly_cloudy_night ||
               iconRes == R.drawable.ic_weather_partly_cloudy ||
               iconRes == R.drawable.ic_weather_partly_cloudy_night ||
               iconRes == R.drawable.ic_weather_fog_cloudy ||
               iconRes == R.drawable.ic_weather_fog_sunny
    }
}
