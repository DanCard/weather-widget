package com.weatherwidget.util

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.LocalDateTime

object DailyForecastIconResolver {
    private const val NWS_CHANCE_RAIN_MIXED_MAX_DAILY_POP = 39
    private val nwsChanceRainMixedConditions =
        listOf(
            "chance light rain",
            "slight chance light rain",
        )

    fun resolveIcon(
        weather: ForecastEntity?,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
    ): Int {
        if (weather == null) return R.drawable.ic_weather_unknown

        val source = WeatherSource.fromId(weather.source)
        val nativeToken = weather.nativeDailyIconToken?.trim().orEmpty()
        if (nativeToken.isNotEmpty()) {
            resolveNativeTokenIcon(weather, source, nativeToken, targetDate, now, latitude, longitude)?.let { return it }
        }

        val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
        return WeatherIconMapper.getIconResource(
            condition = weather.condition,
            isNight = isNight,
        )
    }

    private fun resolveNativeTokenIcon(
        weather: ForecastEntity,
        source: WeatherSource,
        nativeToken: String,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
    ): Int? {
        return when (source) {
            WeatherSource.OPEN_METEO -> nativeToken.toIntOrNull()?.let { code ->
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(
                    condition = OpenMeteoConditionMapper.toCondition(code),
                    isNight = isNight,
                )
            }
            WeatherSource.VISUAL_CROSSING -> visualCrossingIcon(nativeToken)
            WeatherSource.OPEN_WEATHER_MAP -> openWeatherMapIcon(nativeToken)
            WeatherSource.WEATHER_API -> weatherApiIcon(nativeToken)
            WeatherSource.SILURIAN -> silurianIcon(nativeToken, targetDate, now, latitude, longitude)
            WeatherSource.NWS -> {
                nwsChanceRainMixedIcon(weather = weather, nativeToken = nativeToken)?.let { return it }
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(condition = nativeToken, isNight = isNight)
            }
            WeatherSource.GENERIC_GAP -> null
        }
    }

    private fun nwsChanceRainMixedIcon(
        weather: ForecastEntity,
        nativeToken: String,
    ): Int? {
        val normalized = nativeToken.lowercase()
        val dailyPop = weather.precipProbability
        return if (
            nwsChanceRainMixedConditions.any { normalized.contains(it) } &&
            dailyPop != null &&
            dailyPop <= NWS_CHANCE_RAIN_MIXED_MAX_DAILY_POP
        ) {
            R.drawable.ic_weather_partly_cloudy_chance_rain
        } else {
            null
        }
    }

    private fun visualCrossingIcon(nativeToken: String): Int? {
        return when (nativeToken.lowercase()) {
            "clear-day" -> R.drawable.ic_weather_clear
            "clear-night" -> R.drawable.ic_weather_night
            "partly-cloudy-day" -> R.drawable.ic_weather_partly_cloudy
            "partly-cloudy-night" -> R.drawable.ic_weather_partly_cloudy_night
            "cloudy", "overcast" -> R.drawable.ic_weather_cloudy
            "rain", "showers-day", "showers-night" -> R.drawable.ic_weather_rain
            "snow", "snow-showers-day", "snow-showers-night" -> R.drawable.ic_weather_snow
            "thunder-rain", "thunder-showers-day", "thunder-showers-night", "thunder", "storm" ->
                R.drawable.ic_weather_storm
            "fog" -> R.drawable.ic_weather_fog
            "wind" -> R.drawable.ic_weather_wind
            else -> null
        }
    }

    private fun openWeatherMapIcon(nativeToken: String): Int? {
        return when (nativeToken.lowercase()) {
            "01d" -> R.drawable.ic_weather_clear
            "01n" -> R.drawable.ic_weather_night
            "02d" -> R.drawable.ic_weather_mostly_clear
            "02n" -> R.drawable.ic_weather_partly_cloudy_night
            "03d", "03n" -> R.drawable.ic_weather_mostly_cloudy
            "04d", "04n" -> R.drawable.ic_weather_cloudy
            "09d", "09n", "10d", "10n" -> R.drawable.ic_weather_rain
            "11d", "11n" -> R.drawable.ic_weather_storm
            "13d", "13n" -> R.drawable.ic_weather_snow
            "50d", "50n" -> R.drawable.ic_weather_fog
            else -> null
        }
    }

    private fun weatherApiIcon(nativeToken: String): Int? {
        val weatherApiCode = nativeToken.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: return null
        return when (weatherApiCode) {
            1000 -> R.drawable.ic_weather_clear
            1003 -> R.drawable.ic_weather_partly_cloudy
            1006 -> R.drawable.ic_weather_mostly_cloudy
            1009 -> R.drawable.ic_weather_cloudy
            1030, 1135, 1147 -> R.drawable.ic_weather_fog
            1063, 1150, 1153, 1180, 1183, 1186, 1189, 1192, 1195, 1240, 1243, 1246 -> R.drawable.ic_weather_rain
            1066, 1114, 1117, 1210, 1213, 1216, 1219, 1222, 1225, 1237, 1255, 1258, 1261, 1264 ->
                R.drawable.ic_weather_snow
            1069, 1072, 1168, 1171, 1198, 1201, 1204, 1207, 1249, 1252 ->
                R.drawable.ic_weather_rain
            1087, 1273, 1276, 1279, 1282 -> R.drawable.ic_weather_storm
            else -> null
        }
    }

    private fun silurianIcon(
        nativeToken: String,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
    ): Int {
        val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
        return WeatherIconMapper.getIconResource(condition = nativeToken, isNight = isNight)
    }

    private object OpenMeteoConditionMapper {
        fun toCondition(code: Int): String =
            when (code) {
                0 -> "Clear"
                1 -> "Mostly Clear"
                2 -> "Partly Cloudy"
                3 -> "Cloudy"
                45, 48 -> "Fog"
                51, 53, 55, 56, 57 -> "Drizzle"
                61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
                71, 73, 75, 77, 85, 86 -> "Snow"
                95, 96, 99 -> "Thunderstorms"
                else -> "Unknown"
            }
    }
}
