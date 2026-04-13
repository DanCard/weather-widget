package com.weatherwidget.util

import com.weatherwidget.R

object WeatherIconMapper {
    private const val FULLY_CLOUDY_THRESHOLD = 97
    private const val MOSTLY_CLOUDY_UPPER_THRESHOLD = 90

    fun getIconResource(
        condition: String?,
        isNight: Boolean = false,
        cloudCover: Int? = null,
        precipProbability: Int? = null,
    ): Int {
        if (condition == null) return R.drawable.ic_weather_unknown

        val lowerCondition = condition.lowercase()
        val normalizedCondition = normalizePatchyFogTransitionCondition(lowerCondition)
        val isSlightChance = normalizedCondition.contains("slight chance") || normalizedCondition.contains("patchy")
        val isSubOvercastCloudy =
            normalizedCondition.contains("cloudy") &&
                !normalizedCondition.contains("mostly cloudy") &&
                !normalizedCondition.contains("partly") &&
                cloudCover != null &&
                cloudCover < FULLY_CLOUDY_THRESHOLD
        
        return when {
            normalizedCondition.contains("thunder") || normalizedCondition.contains("storm") -> {
                if (isSlightChance) {
                    slightChanceCloudCoverIcon(isNight, cloudCover)
                } else R.drawable.ic_weather_storm
            }
            normalizedCondition.contains("snow") || normalizedCondition.contains("flurries") || normalizedCondition.contains("blizzard") -> {
                if (isSlightChance) {
                    slightChanceCloudCoverIcon(isNight, cloudCover)
                } else R.drawable.ic_weather_snow
            }
            normalizedCondition.contains("rain") || normalizedCondition.contains("drizzle") || normalizedCondition.contains("shower") -> {
                val effectiveProb = precipProbability ?: if (isSlightChance) 20 else null
                getPrecipitationIcon(isNight, cloudCover, effectiveProb, R.drawable.ic_weather_rain)
            }
            normalizedCondition.contains("fog") && (normalizedCondition.contains("sunny") || normalizedCondition.contains("clear")) -> {
                if (isNight) R.drawable.ic_weather_fog_night else R.drawable.ic_weather_fog_sunny
            }
            normalizedCondition.contains("fog") && (normalizedCondition.contains("cloudy") || normalizedCondition.contains("overcast")) -> R.drawable.ic_weather_fog_cloudy
            normalizedCondition.contains("dense fog") -> R.drawable.ic_weather_fog_dense
            normalizedCondition.contains("patchy fog") || normalizedCondition.contains("light fog") -> {
                if (isNight) R.drawable.ic_weather_fog_light_night else R.drawable.ic_weather_fog_light
            }
            normalizedCondition.contains(
                "fog",
            ) || normalizedCondition.contains("mist") || normalizedCondition.contains("haze") -> {
                if (isNight) R.drawable.ic_weather_fog_night else R.drawable.ic_weather_fog
            }
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
            normalizedCondition.contains("overcast") -> {
                if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
            }
            isSubOvercastCloudy -> {
                if (isNight) R.drawable.ic_weather_mostly_cloudy_night else R.drawable.ic_weather_mostly_cloudy
            }
            normalizedCondition.contains("cloudy") -> R.drawable.ic_weather_cloudy
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

    private fun getPrecipitationIcon(
        isNight: Boolean,
        cloudCover: Int?,
        precipProbability: Int?,
        baseRainIcon: Int
    ): Int {
        // 1. Threshold: If probability is null or high (>= 50%), use the base rain icon (legacy/heavy)
        if (precipProbability == null || precipProbability >= 50) return baseRainIcon

        // 2. Threshold: If probability is trace (< 10%), show cloud cover icon only
        if (precipProbability < 10) return getCloudCoverIcon(isNight, cloudCover)

        // 3. Nuanced Matrix (10% - 49%): Select between 1-drop and 2-drop variants based on cloud cover tiers
        val isTwoDrops = precipProbability >= 35
        val cloudTier = when (cloudCover ?: 50) {
            in 0..30 -> 0 // Mostly Clear
            in 31..70 -> 1 // Partly Cloudy
            else -> 2 // Mostly Cloudy/Overcast
        }

        return when (cloudTier) {
            0 -> { // Mostly Clear
                if (isTwoDrops) {
                    if (isNight) R.drawable.ic_weather_night_chance_rain else R.drawable.ic_weather_clear_chance_rain
                } else {
                    if (isNight) R.drawable.ic_weather_night_slight_chance_rain else R.drawable.ic_weather_clear_slight_chance_rain
                }
            }
            1 -> { // Partly Cloudy
                if (isTwoDrops) {
                    if (isNight) R.drawable.ic_weather_partly_cloudy_chance_rain_night else R.drawable.ic_weather_partly_cloudy_chance_rain
                } else {
                    if (isNight) R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night else R.drawable.ic_weather_partly_cloudy_slight_chance_rain
                }
            }
            else -> { // Mostly Cloudy/Overcast
                if (isTwoDrops) R.drawable.ic_weather_cloudy_chance_rain else R.drawable.ic_weather_cloudy_slight_chance_rain
            }
        }
    }

    private fun getCloudCoverIcon(isNight: Boolean, cloudCover: Int?): Int {
        if (cloudCover == null) return if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
        return when (cloudCover.coerceIn(0, 100)) {
            in 0..25 -> if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
            in 26..74 -> if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
            in 75..MOSTLY_CLOUDY_UPPER_THRESHOLD -> if (isNight) R.drawable.ic_weather_mostly_cloudy_night else R.drawable.ic_weather_mostly_cloudy
            else -> R.drawable.ic_weather_cloudy
        }
    }

    private fun slightChanceCloudCoverIcon(isNight: Boolean, cloudCover: Int?): Int {
        return getCloudCoverIcon(isNight, cloudCover)
    }

    private fun normalizePatchyFogTransitionCondition(condition: String): String {
        if (!condition.contains("patchy fog")) return condition
        val thenIndex = condition.indexOf(" then ")
        if (thenIndex == -1) return condition
        return condition.substring(thenIndex + " then ".length).trim()
    }

    fun isSunny(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_clear ||
               iconRes == R.drawable.ic_weather_mostly_clear
    }

    fun isRainy(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_rain ||
               iconRes == R.drawable.ic_weather_storm ||
               iconRes == R.drawable.ic_weather_snow
    }

    fun isRainIndicator(iconRes: Int): Boolean {
        return isRainy(iconRes) ||
               iconRes == R.drawable.ic_weather_partly_cloudy_chance_rain ||
               iconRes == R.drawable.ic_weather_partly_cloudy_chance_rain_night ||
               iconRes == R.drawable.ic_weather_partly_cloudy_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night ||
               iconRes == R.drawable.ic_weather_clear_chance_rain ||
               iconRes == R.drawable.ic_weather_clear_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_night_chance_rain ||
               iconRes == R.drawable.ic_weather_night_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_cloudy_chance_rain ||
               iconRes == R.drawable.ic_weather_cloudy_slight_chance_rain
    }

    fun isMixed(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_weather_mostly_cloudy ||
               iconRes == R.drawable.ic_weather_mostly_cloudy_night ||
               iconRes == R.drawable.ic_weather_partly_cloudy ||
               iconRes == R.drawable.ic_weather_partly_cloudy_night ||
               iconRes == R.drawable.ic_weather_partly_cloudy_chance_rain ||
               iconRes == R.drawable.ic_weather_partly_cloudy_chance_rain_night ||
               iconRes == R.drawable.ic_weather_partly_cloudy_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night ||
               iconRes == R.drawable.ic_weather_clear_chance_rain ||
               iconRes == R.drawable.ic_weather_clear_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_night_chance_rain ||
               iconRes == R.drawable.ic_weather_night_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_cloudy_chance_rain ||
               iconRes == R.drawable.ic_weather_cloudy_slight_chance_rain ||
               iconRes == R.drawable.ic_weather_fog_cloudy ||
               iconRes == R.drawable.ic_weather_fog_sunny ||
               iconRes == R.drawable.ic_weather_fog_night ||
               iconRes == R.drawable.ic_weather_fog_light ||
               iconRes == R.drawable.ic_weather_fog_light_night
    }

    fun isCloudForecastEligible(iconRes: Int): Boolean {
        return isMixed(iconRes) ||
               iconRes == R.drawable.ic_weather_fog ||
               iconRes == R.drawable.ic_weather_fog_light ||
               iconRes == R.drawable.ic_weather_fog_dense ||
               iconRes == R.drawable.ic_weather_cloudy ||
               iconRes == R.drawable.ic_weather_mostly_clear
    }
}
