package com.weatherwidget.util

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object DailyForecastIconResolver {

    data class DayNightPrecip(
        val dayMax: Int?,
        val nightMax: Int?,
    )

    fun getMinimumPrecipProbabilityDay(daysFromToday: Int): Int =
        com.weatherwidget.shared.util.DailyRainLabels.getMinimumPrecipProbabilityDay(daysFromToday)

    fun getMinimumPrecipProbabilityNight(daysFromToday: Int): Int =
        com.weatherwidget.shared.util.DailyRainLabels.getMinimumPrecipProbabilityNight(daysFromToday)

    @Deprecated("Use getMinimumPrecipProbabilityDay() for clarity", replaceWith = ReplaceWith("getMinimumPrecipProbabilityDay(daysFromToday)"))
    fun getMinimumPrecipProbability(daysFromToday: Int): Int = getMinimumPrecipProbabilityDay(daysFromToday)

    fun calculateDayNightPrecipProbabilities(
        hourlyForecasts: List<HourlyForecastEntity>,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
        displaySource: WeatherSource,
    ): DayNightPrecip {
        val shared = com.weatherwidget.shared.util.DailyRainLabels.calculateDayNightPrecipProbabilities(
            hourly = hourlyForecasts.map {
                com.weatherwidget.data.model.HourlyForecast(
                    dateTime = it.dateTime,
                    temperature = it.temperature,
                    condition = it.condition,
                    precipProbability = it.precipProbability,
                    source = it.source,
                )
            },
            targetDate = targetDate,
            displaySourceId = displaySource.id,
        )
        return DayNightPrecip(dayMax = shared.dayMax, nightMax = shared.nightMax)
    }

    /**
     * Day/night precip % used for the daily label and icon, via the shared selection so Android and
     * desktop stay identical (hourly 8am–8pm / 8pm–8am window max, period fields as fallback).
     * Maps the Android hourly entities to the shared model.
     */
    fun resolveDailyLabelPrecip(
        weather: ForecastEntity?,
        hourlyForecasts: List<HourlyForecastEntity>,
        targetDate: LocalDate,
        isPast: Boolean,
        displaySource: WeatherSource,
        actual: com.weatherwidget.data.model.DailyHistory? = null,
    ): com.weatherwidget.shared.util.DailyRainLabels.ResolvedDailyPrecip =
        com.weatherwidget.shared.util.DailyRainLabels.resolveDailyLabelPrecip(
            isPast = isPast,
            displaySourceId = displaySource.id,
            daytimePrecipProbability = weather?.daytimePrecipProbability,
            nighttimePrecipProbability = weather?.nighttimePrecipProbability,
            precipProbability = weather?.precipProbability,
            hourly = hourlyForecasts.map {
                com.weatherwidget.data.model.HourlyForecast(
                    dateTime = it.dateTime,
                    temperature = it.temperature,
                    condition = it.condition,
                    precipProbability = it.precipProbability,
                    source = it.source,
                )
            },
            targetDate = targetDate,
            storedDayPrecipChance = actual?.forecastDayPrecipChance,
            storedNightPrecipChance = actual?.forecastNightPrecipChance,
        )

    fun resolveIcon(
        weather: ForecastEntity?,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
        cloudCover: Int? = null,
    ): Int {
        if (weather == null) return R.drawable.ic_weather_unknown

        val source = WeatherSource.fromId(weather.source)
        val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
        val daysFromToday = ChronoUnit.DAYS.between(now.toLocalDate(), targetDate).toInt()

        val nativeToken = weather.nativeDailyIconToken?.trim().orEmpty()
        if (nativeToken.isNotEmpty()) {
            resolveNativeTokenIcon(weather, source, nativeToken, targetDate, now, latitude, longitude, cloudCover)?.let { icon ->
                if (shouldSuppressRainIcon(icon, weather.precipProbability, daysFromToday, isNight, dayPrecipProbability, nightPrecipProbability)) {
                    return WeatherIconMapper.getCloudCoverIcon(isNight, cloudCover)
                }
                return applyPartlyCloudyFloor(icon, cloudCover, isNight)
            }
        }

        val icon = WeatherIconMapper.getIconResource(
            condition = weather.condition,
            isNight = isNight,
            cloudCover = cloudCover,
            precipProbability = weather.precipProbability,
        )

        if (shouldSuppressRainIcon(icon, weather.precipProbability, daysFromToday, isNight, dayPrecipProbability, nightPrecipProbability)) {
            return WeatherIconMapper.getCloudCoverIcon(isNight, cloudCover)
        }

        return applyPartlyCloudyFloor(icon, cloudCover, isNight)
    }

    /**
     * Daily-only floor (shared rule, see [WeatherConditionResolver.applyDailyPartlyCloudyFloor]):
     * a worded "partly cloudy" must ALSO be at least the shared min cloud cover at noon, else it's
     * really only a few clouds → downgrade to the "mostly clear" tier.
     */
    private fun applyPartlyCloudyFloor(icon: Int, cloudCover: Int?, isNight: Boolean): Int {
        val isPartlyCloudy = icon == R.drawable.ic_weather_partly_cloudy ||
            icon == R.drawable.ic_weather_partly_cloudy_night
        return if (isPartlyCloudy && cloudCover != null &&
            cloudCover < com.weatherwidget.shared.util.WeatherConditionResolver.PARTLY_CLOUDY_MIN_CLOUD_COVER) {
            WeatherIconMapper.getCloudCoverIcon(isNight, cloudCover)
        } else {
            icon
        }
    }

    internal fun shouldSuppressRainIcon(
        icon: Int,
        dailyPrecipProbability: Int?,
        daysFromToday: Int,
        isNight: Boolean,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
    ): Boolean {
        if (!WeatherIconMapper.isRainIndicator(icon)) return false

        val dayMinProb = getMinimumPrecipProbabilityDay(daysFromToday)
        val nightMinProb = getMinimumPrecipProbabilityNight(daysFromToday)

        val dayPrecip = dayPrecipProbability
        val nightPrecip = nightPrecipProbability

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
        cloudCover: Int? = null,
    ): Int? {
        return when (source) {
            WeatherSource.OPEN_METEO -> nativeToken.toIntOrNull()?.let { code ->
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(
                    condition = OpenMeteoConditionMapper.toCondition(code),
                    isNight = isNight,
                    cloudCover = cloudCover,
                    precipProbability = weather.precipProbability,
                )
            }
            WeatherSource.VISUAL_CROSSING -> visualCrossingIcon(nativeToken)
            WeatherSource.OPEN_WEATHER_MAP -> openWeatherMapIcon(nativeToken)
            WeatherSource.WEATHER_API -> weatherApiIcon(nativeToken)
            WeatherSource.SILURIAN -> silurianIcon(nativeToken, targetDate, now, latitude, longitude, weather.precipProbability, cloudCover)
            WeatherSource.TOMORROW_IO -> nativeToken.toIntOrNull()?.let { code ->
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(
                    condition = TomorrowIoConditionMapper.toCondition(code),
                    isNight = isNight,
                    cloudCover = cloudCover,
                    precipProbability = weather.precipProbability,
                )
            }
            WeatherSource.NWS -> {
                val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
                WeatherIconMapper.getIconResource(
                    condition = nativeToken,
                    isNight = isNight,
                    cloudCover = cloudCover,
                    precipProbability = weather.precipProbability,
                )
            }
            // Neither produces a daily forecast row, so neither ever reaches this resolver:
            // GENERIC_GAP carries no condition token, and METAR is an observation-only feed with
            // no forecast product at all.
            WeatherSource.GENERIC_GAP, WeatherSource.METAR, WeatherSource.SYNOPTIC -> null
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
        precipProbability: Int?,
        cloudCover: Int?,
    ): Int {
        val isNight = targetDate == now.toLocalDate() && SunPositionUtils.isNight(now, latitude, longitude)
        // Thread Silurian's own precip probability (and noon cloud %) through, exactly like every other
        // source's branch. Silurian labels near-dry days with a "Rain" token (e.g. 5% chance); without
        // the probability, getPrecipitationIcon short-circuits to the solid rain icon and the day paints
        // a steel-blue "rainy" bar. With it, the resolver's <8% downgrade turns that into a cloud-cover
        // icon, matching the desktop model which already threads precipProbability into resolveIconName.
        return WeatherIconMapper.getIconResource(
            condition = nativeToken,
            isNight = isNight,
            cloudCover = cloudCover,
            precipProbability = precipProbability,
        )
    }

    private object OpenMeteoConditionMapper {
        fun toCondition(code: Int): String =
            when (code) {
                0 -> "Clear"
                1 -> "Mostly Clear"
                2 -> "Partly Cloudy"
                3 -> "Cloudy"
                45 -> "Light Fog"
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
