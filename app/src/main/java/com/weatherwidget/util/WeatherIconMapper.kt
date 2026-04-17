package com.weatherwidget.util

import androidx.annotation.VisibleForTesting
import com.weatherwidget.R

object WeatherIconMapper {
    private const val FULLY_CLOUDY_THRESHOLD = 97
    private const val MOSTLY_CLOUDY_UPPER_THRESHOLD = 90

    private val PRECIPITATION_ICONS = setOf(
        R.drawable.ic_weather_rain,
        R.drawable.ic_weather_storm,
        R.drawable.ic_weather_snow
    )

    private val RAIN_INDICATOR_ICONS = PRECIPITATION_ICONS + setOf(
        R.drawable.ic_weather_partly_cloudy_chance_rain,
        R.drawable.ic_weather_partly_cloudy_chance_rain_night,
        R.drawable.ic_weather_partly_cloudy_slight_chance_rain,
        R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night,
        R.drawable.ic_weather_cloudy_chance_rain,
        R.drawable.ic_weather_cloudy_slight_chance_rain
    )

    @VisibleForTesting
    internal val MIXED_ICONS = setOf(
        R.drawable.ic_weather_mostly_cloudy,
        R.drawable.ic_weather_mostly_cloudy_night,
        R.drawable.ic_weather_mostly_clear,
        R.drawable.ic_weather_partly_cloudy,
        R.drawable.ic_weather_partly_cloudy_night,
        R.drawable.ic_weather_horizon_sun,
        R.drawable.ic_weather_partly_cloudy_chance_rain,
        R.drawable.ic_weather_partly_cloudy_chance_rain_night,
        R.drawable.ic_weather_partly_cloudy_slight_chance_rain,
        R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night,
        R.drawable.ic_weather_cloudy_chance_rain,
        R.drawable.ic_weather_cloudy_slight_chance_rain,
        R.drawable.ic_weather_fog_cloudy,
        R.drawable.ic_weather_fog_sunny,
        R.drawable.ic_weather_fog_night,
        R.drawable.ic_weather_fog_light,
        R.drawable.ic_weather_fog_light_night
    )

    private val CLOUD_FORECAST_ELIGIBLE_ICONS = MIXED_ICONS + setOf(
        R.drawable.ic_weather_fog,
        R.drawable.ic_weather_fog_dense,
        R.drawable.ic_weather_cloudy,
        R.drawable.ic_weather_mostly_clear
    )

    fun getIconResource(
        condition: String?,
        isNight: Boolean = false,
        cloudCover: Int? = null,
        precipProbability: Int? = null,
        isTwilight: Boolean = false,
        isSunBoundary: Boolean = false,
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
            normalizedCondition.contains("thunder") || normalizedCondition.contains("storm") || normalizedCondition.contains("hail") -> {
                val effectiveProb = precipProbability ?: if (isSlightChance) 20 else null
                getPrecipitationIcon(isNight, cloudCover, effectiveProb, R.drawable.ic_weather_storm)
            }
            normalizedCondition.contains("snow") || normalizedCondition.contains("flurries") || normalizedCondition.contains("blizzard") || normalizedCondition.contains("sleet") || normalizedCondition.contains("ice pellet") -> {
                val effectiveProb = precipProbability ?: if (isSlightChance) 20 else null
                getPrecipitationIcon(isNight, cloudCover, effectiveProb, R.drawable.ic_weather_snow)
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
                if (isSunBoundary) R.drawable.ic_weather_horizon_sun
                else if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
            }
            normalizedCondition.contains("partly") -> {
                if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
            }
            normalizedCondition.contains("overcast") -> {
                if (isSunBoundary) R.drawable.ic_weather_horizon_sun
                else if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
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
                if (isSunBoundary) R.drawable.ic_weather_horizon_sun
                else if (isNight) R.drawable.ic_weather_night
                else R.drawable.ic_weather_clear
            }
            else -> {
                if (isSunBoundary) R.drawable.ic_weather_horizon_sun
                else if (isNight) R.drawable.ic_weather_night
                else R.drawable.ic_weather_clear
            }
        }
    }

    private fun getPrecipitationIcon(
        isNight: Boolean,
        cloudCover: Int?,
        precipProbability: Int?,
        baseRainIcon: Int
    ): Int {
        if (precipProbability == null || precipProbability >= 80) return baseRainIcon
        if (precipProbability <= 15) return getCloudCoverIcon(isNight, cloudCover)

        val isChance = precipProbability >= 60
        val cloudPct = cloudCover ?: 50

        val result = when {
            // 1. Overcast Tier (71%+ cloud)
            cloudPct > 70 ->
                if (isChance) R.drawable.ic_weather_cloudy_chance_rain
                else R.drawable.ic_weather_cloudy_slight_chance_rain

            // 2. Partly Cloudy Tier (31-70% cloud)
            cloudPct > 30 -> when {
                isNight && isChance -> R.drawable.ic_weather_partly_cloudy_chance_rain_night
                isNight -> R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night
                isChance -> R.drawable.ic_weather_partly_cloudy_chance_rain
                else -> R.drawable.ic_weather_partly_cloudy_slight_chance_rain
            }

            // 3. Clear Tier (0-30% cloud) - Redirect to Partly Cloudy as requested
            else -> when {
                isNight && isChance -> R.drawable.ic_weather_partly_cloudy_chance_rain_night
                isNight -> R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night
                isChance -> R.drawable.ic_weather_partly_cloudy_chance_rain
                else -> R.drawable.ic_weather_partly_cloudy_slight_chance_rain
            }
        }
        android.util.Log.d("WeatherIconMapper", "getPrecipitationIcon: prob=$precipProbability% (isChance=$isChance) cloud=$cloudPct% -> icon=${result}")
        return result
    }

    fun getCloudCoverIcon(isNight: Boolean, cloudCover: Int?): Int {
        if (cloudCover == null) return if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
        return when (cloudCover.coerceIn(0, 100)) {
            in 0..25 -> if (isNight) R.drawable.ic_weather_night else R.drawable.ic_weather_mostly_clear
            in 26..74 -> if (isNight) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy
            in 75..MOSTLY_CLOUDY_UPPER_THRESHOLD -> if (isNight) R.drawable.ic_weather_mostly_cloudy_night else R.drawable.ic_weather_mostly_cloudy
            else -> R.drawable.ic_weather_cloudy
        }
    }

    private fun normalizePatchyFogTransitionCondition(condition: String): String {
        if (!condition.contains("patchy fog")) return condition
        val thenIndex = condition.indexOf(" then ")
        if (thenIndex == -1) return condition
        return condition.substring(thenIndex + " then ".length).trim()
    }

    fun isSunny(iconRes: Int): Boolean = iconRes in setOf(R.drawable.ic_weather_clear, R.drawable.ic_weather_mostly_clear, R.drawable.ic_weather_horizon_sun)

    fun isPrecipitation(iconRes: Int): Boolean = iconRes in PRECIPITATION_ICONS

    fun isRainIndicator(iconRes: Int): Boolean = iconRes in RAIN_INDICATOR_ICONS

    fun isMixed(iconRes: Int): Boolean = iconRes in MIXED_ICONS

    fun isCloudForecastEligible(iconRes: Int): Boolean = iconRes in CLOUD_FORECAST_ELIGIBLE_ICONS
}
