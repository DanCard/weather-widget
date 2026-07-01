package com.weatherwidget.util

import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags

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

    /** Map from shared icon name to Android drawable resource ID. */
    private val NAME_TO_RES: Map<String, Int> = mapOf(
        WeatherConditionResolver.IC_UNKNOWN to R.drawable.ic_weather_unknown,
        WeatherConditionResolver.IC_CLEAR to R.drawable.ic_weather_clear,
        WeatherConditionResolver.IC_NIGHT to R.drawable.ic_weather_night,
        WeatherConditionResolver.IC_MOSTLY_CLEAR to R.drawable.ic_weather_mostly_clear,
        WeatherConditionResolver.IC_PARTLY_CLOUDY to R.drawable.ic_weather_partly_cloudy,
        WeatherConditionResolver.IC_PARTLY_CLOUDY_NIGHT to R.drawable.ic_weather_partly_cloudy_night,
        WeatherConditionResolver.IC_MOSTLY_CLOUDY to R.drawable.ic_weather_mostly_cloudy,
        WeatherConditionResolver.IC_MOSTLY_CLOUDY_NIGHT to R.drawable.ic_weather_mostly_cloudy_night,
        WeatherConditionResolver.IC_CLOUDY to R.drawable.ic_weather_cloudy,
        WeatherConditionResolver.IC_RAIN to R.drawable.ic_weather_rain,
        WeatherConditionResolver.IC_STORM to R.drawable.ic_weather_storm,
        WeatherConditionResolver.IC_SNOW to R.drawable.ic_weather_snow,
        WeatherConditionResolver.IC_FOG to R.drawable.ic_weather_fog,
        WeatherConditionResolver.IC_FOG_DENSE to R.drawable.ic_weather_fog_dense,
        WeatherConditionResolver.IC_FOG_LIGHT to R.drawable.ic_weather_fog_light,
        WeatherConditionResolver.IC_FOG_LIGHT_NIGHT to R.drawable.ic_weather_fog_light_night,
        WeatherConditionResolver.IC_FOG_NIGHT to R.drawable.ic_weather_fog_night,
        WeatherConditionResolver.IC_FOG_SUNNY to R.drawable.ic_weather_fog_sunny,
        WeatherConditionResolver.IC_FOG_CLOUDY to R.drawable.ic_weather_fog_cloudy,
        WeatherConditionResolver.IC_WIND to R.drawable.ic_weather_wind,
        WeatherConditionResolver.IC_HORIZON_SUN to R.drawable.ic_weather_horizon_sun,
        WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN to R.drawable.ic_weather_partly_cloudy_chance_rain,
        WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT to R.drawable.ic_weather_partly_cloudy_chance_rain_night,
        WeatherConditionResolver.IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN to R.drawable.ic_weather_partly_cloudy_slight_chance_rain,
        WeatherConditionResolver.IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT to R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night,
        WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN to R.drawable.ic_weather_cloudy_chance_rain,
        WeatherConditionResolver.IC_CLOUDY_SLIGHT_CHANCE_RAIN to R.drawable.ic_weather_cloudy_slight_chance_rain,
    )

    private val RES_TO_NAME: Map<Int, String> = NAME_TO_RES.entries.associate { (name, res) -> res to name }

    private fun iconNameToRes(iconName: String): Int =
        NAME_TO_RES[iconName] ?: R.drawable.ic_weather_unknown

    fun iconResToName(iconRes: Int): String? = RES_TO_NAME[iconRes]

    fun getIconResource(
        condition: String?,
        isNight: Boolean = false,
        cloudCover: Int? = null,
        precipProbability: Int? = null,
        isTwilight: Boolean = false,
        isSunBoundary: Boolean = false,
    ): Int {
        val iconName = WeatherConditionResolver.resolveIconName(
            condition = condition,
            isNight = isNight,
            cloudCover = cloudCover,
            precipProbability = precipProbability,
            isTwilight = isTwilight,
            isSunBoundary = isSunBoundary,
        )
        val result = iconNameToRes(iconName)
        android.util.Log.d("WeatherIconMapper", "getIconResource: condition=$condition isNight=$isNight cloudCover=$cloudCover -> ${if (result == R.drawable.ic_weather_night) "ic_weather_night" else if (result == R.drawable.ic_weather_partly_cloudy_night) "ic_weather_partly_cloudy_night" else "icon=$result"}")
        return result
    }

    fun getCloudCoverIcon(isNight: Boolean, cloudCover: Int?): Int {
        val iconName = WeatherConditionResolver.getCloudCoverIcon(isNight, cloudCover)
        return iconNameToRes(iconName)
    }

    fun getConditionFlags(iconName: String, isNight: Boolean = false): ConditionFlags {
        return WeatherConditionResolver.getConditionFlags(iconName, isNight)
    }

    fun isSunny(iconRes: Int): Boolean = iconRes in setOf(R.drawable.ic_weather_clear, R.drawable.ic_weather_night, R.drawable.ic_weather_mostly_clear, R.drawable.ic_weather_horizon_sun)

    fun isPrecipitation(iconRes: Int): Boolean = iconRes in PRECIPITATION_ICONS

    fun isRainIndicator(iconRes: Int): Boolean = iconRes in RAIN_INDICATOR_ICONS

    fun isMixed(iconRes: Int): Boolean = iconRes in MIXED_ICONS

    fun isCloudForecastEligible(iconRes: Int): Boolean = iconRes in CLOUD_FORECAST_ELIGIBLE_ICONS
}
