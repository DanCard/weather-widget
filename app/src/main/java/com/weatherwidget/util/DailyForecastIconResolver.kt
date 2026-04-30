package com.weatherwidget.util

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object DailyForecastIconResolver {

    data class DayNightPrecip(
        val dayMax: Int?,
        val nightMax: Int?,
    )

    fun getMinimumPrecipProbabilityDay(daysFromToday: Long): Int {
        /*
            - day 0: 10.0 → 10
            - day 1: 12.33 → 12
            - day 2: 14.67 → 14
            - day 3: 17.0 → 17
            - day 4: 19.33 → 19
            - day 5: 21.67 → 21
            - day 6: 24.0 → 24
            - day 7: 26.33 → 26
            - day 8: 28.67 → 28
            - day 9: 31.0 → 31
            - day 10+: 33 (capped)
        */
        return (7.0 / 3.0 * daysFromToday + 10).toInt().coerceIn(0, 33)
    }

    fun getMinimumPrecipProbabilityNight(daysFromToday: Long): Int {
        /*
            - day 0: 29.0 → 29
            - day 1: 32.5 → 32
            - day 2: 36.0 → 36
            - day 3: 39.5 → 39
            - day 4: 43.0 → 43
            - day 5: 46.5 → 46
            - day 6: 50.0 → 50
            - day 7+: 51 (capped)
        */
        return (3.5 * daysFromToday + 29).toInt().coerceIn(0, 51)
    }

    @Deprecated("Use getMinimumPrecipProbabilityDay() for clarity", replaceWith = ReplaceWith("getMinimumPrecipProbabilityDay(daysFromToday)"))
    fun getMinimumPrecipProbability(daysFromToday: Long): Int = getMinimumPrecipProbabilityDay(daysFromToday)

    fun calculateDayNightPrecipProbabilities(
        hourlyForecasts: List<HourlyForecastEntity>,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
        displaySource: WeatherSource,
    ): DayNightPrecip {
        val zoneId = ZoneId.systemDefault()
        val startMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val sunTimes = SunPositionUtils.getSunTimes(
            targetDate.atTime(12, 0),
            latitude,
            longitude,
        )
        val sunriseHour = sunTimes.sunriseHour
        val sunsetHour = sunTimes.sunsetHour

        val sourceForecasts = hourlyForecasts.filter { it.source == displaySource.id }
        val candidateForecasts = if (sourceForecasts.isNotEmpty()) sourceForecasts
            else hourlyForecasts.filter { it.source == WeatherSource.GENERIC_GAP.id }

        val dayPrecips = mutableListOf<Int>()
        val nightPrecips = mutableListOf<Int>()

        for (hourly in candidateForecasts) {
            if (hourly.dateTime < startMs || hourly.dateTime >= endMs) continue
            if (hourly.precipProbability == null) continue

            val offsetMs = hourly.dateTime - startMs
            val hourOfDay = offsetMs / (3_600_000.0)
            if (hourOfDay >= sunriseHour && hourOfDay < sunsetHour) {
                dayPrecips.add(hourly.precipProbability)
            } else {
                nightPrecips.add(hourly.precipProbability)
            }
        }

        return DayNightPrecip(
            dayMax = dayPrecips.maxOrNull(),
            nightMax = nightPrecips.maxOrNull(),
        )
    }

    fun resolveIcon(
        weather: ForecastEntity?,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
    ): Int {
        if (weather == null) return R.drawable.ic_weather_unknown

        val source = WeatherSource.fromId(weather.source)
        val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
        val daysFromToday = ChronoUnit.DAYS.between(now.toLocalDate(), targetDate)

        val nativeToken = weather.nativeDailyIconToken?.trim().orEmpty()
        if (nativeToken.isNotEmpty()) {
            resolveNativeTokenIcon(weather, source, nativeToken, targetDate, now, latitude, longitude)?.let { icon ->
                if (shouldSuppressRainIcon(icon, weather.precipProbability, daysFromToday, isNight, dayPrecipProbability, nightPrecipProbability)) {
                    return WeatherIconMapper.getCloudCoverIcon(isNight, null)
                }
                return icon
            }
        }

        val icon = WeatherIconMapper.getIconResource(
            condition = weather.condition,
            isNight = isNight,
            precipProbability = weather.precipProbability,
        )

        if (shouldSuppressRainIcon(icon, weather.precipProbability, daysFromToday, isNight, dayPrecipProbability, nightPrecipProbability)) {
            return WeatherIconMapper.getCloudCoverIcon(isNight, null)
        }

        return icon
    }

    internal fun shouldSuppressRainIcon(
        icon: Int,
        dailyPrecipProbability: Int?,
        daysFromToday: Long,
        isNight: Boolean,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
    ): Boolean {
        if (!WeatherIconMapper.isRainIndicator(icon)) return false

        val dayMinProb = getMinimumPrecipProbabilityDay(daysFromToday)
        val nightMinProb = getMinimumPrecipProbabilityNight(daysFromToday)

        val dayPrecip = dayPrecipProbability ?: dailyPrecipProbability
        val nightPrecip = nightPrecipProbability ?: dailyPrecipProbability

        val daySuppresses = dayPrecip != null && dayPrecip < dayMinProb
        val nightSuppresses = nightPrecip != null && nightPrecip < nightMinProb

        return daySuppresses && nightSuppresses
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
                    precipProbability = weather.precipProbability,
                )
            }
            WeatherSource.VISUAL_CROSSING -> visualCrossingIcon(nativeToken)
            WeatherSource.OPEN_WEATHER_MAP -> openWeatherMapIcon(nativeToken)
            WeatherSource.WEATHER_API -> weatherApiIcon(nativeToken)
            WeatherSource.SILURIAN -> silurianIcon(nativeToken, targetDate, now, latitude, longitude)
            WeatherSource.TOMORROW_IO -> nativeToken.toIntOrNull()?.let { code ->
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(
                    condition = TomorrowIoConditionMapper.toCondition(code),
                    isNight = isNight,
                    precipProbability = weather.precipProbability,
                )
            }
            WeatherSource.NWS -> {
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(
                    condition = nativeToken,
                    isNight = isNight,
                    precipProbability = weather.precipProbability,
                )
            }
            WeatherSource.GENERIC_GAP -> null
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
            1030 -> R.drawable.ic_weather_fog_light
            1135 -> R.drawable.ic_weather_fog
            1147 -> R.drawable.ic_weather_fog_dense
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
        return WeatherIconMapper.getIconResource(
            condition = nativeToken,
            isNight = isNight,
            precipProbability = null,
        )
    }

    private object OpenMeteoConditionMapper {
        fun toCondition(code: Int): String =
            when (code) {
                0 -> "Clear"
                1 -> "Mostly Clear"
                2 -> "Partly Cloudy"
                3 -> "Cloudy"
                45 -> "Fog"
                48 -> "Dense Fog"
                51, 53, 55, 56, 57 -> "Drizzle"
                61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
                71, 73, 75, 77, 85, 86 -> "Snow"
                95, 96, 99 -> "Thunderstorms"
                else -> "Unknown"
            }
    }

    private object TomorrowIoConditionMapper {
        fun toCondition(code: Int): String =
            when (code) {
                1000 -> "Clear"
                1100 -> "Mostly Clear"
                1101 -> "Partly Cloudy"
                1102 -> "Mostly Cloudy"
                1001 -> "Cloudy"
                2000, 2100 -> "Fog"
                4000 -> "Drizzle"
                4001, 4200 -> "Rain"
                4201 -> "Heavy Rain"
                5000, 5001, 5100, 5101 -> "Snow"
                6000, 6001, 6200, 6201 -> "Freezing Rain"
                7000, 7101, 7102 -> "Ice Pellets"
                8000 -> "Thunderstorm"
                else -> "Unknown"
            }
    }
}
