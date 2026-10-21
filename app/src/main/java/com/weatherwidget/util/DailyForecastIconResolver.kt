package com.weatherwidget.util

import com.weatherwidget.data.local.hourlySummary
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyIconName
import java.time.LocalDate
import java.time.LocalDateTime

object DailyForecastIconResolver {

    data class DayNightPrecip(
        val dayMax: Int?,
        val nightMax: Int?,
    )

    fun getMinimumPrecipProbabilityDay(daysFromToday: Int): Int =
        com.weatherwidget.shared.util.DailyRainLabels.getMinimumPrecipProbabilityDay(daysFromToday)

    fun getMinimumPrecipProbabilityNight(daysFromToday: Int): Int =
        com.weatherwidget.shared.util.DailyRainLabels.getMinimumPrecipProbabilityNight(daysFromToday)

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
        centerLat: Double? = null,
        centerLon: Double? = null,
    ): com.weatherwidget.shared.util.DailyRainLabels.ResolvedDailyPrecip {
        val sharedHourly = hourlyForecasts.map(HourlyForecastEntity::toHourlyForecast)
        val common = com.weatherwidget.shared.util.DailyRainLabels
        if (centerLat != null && centerLon != null) {
            return common.resolveDailyLabelPrecipAtSite(
                isPast = isPast,
                displaySourceId = displaySource.id,
                daytimePrecipProbability = weather?.daytimePrecipProbability,
                nighttimePrecipProbability = weather?.nighttimePrecipProbability,
                precipProbability = weather?.precipProbability,
                hourly = sharedHourly,
                centerLat = centerLat,
                centerLon = centerLon,
                targetDate = targetDate,
                storedDayPrecipChance = actual?.forecastDayPrecipChance,
                storedNightPrecipChance = actual?.forecastNightPrecipChance,
                hourlySummary = weather?.hourlySummary,
            )
        }
        return common.resolveDailyLabelPrecip(
            isPast = isPast,
            displaySourceId = displaySource.id,
            daytimePrecipProbability = weather?.daytimePrecipProbability,
            nighttimePrecipProbability = weather?.nighttimePrecipProbability,
            precipProbability = weather?.precipProbability,
            hourly = sharedHourly,
            targetDate = targetDate,
            storedDayPrecipChance = actual?.forecastDayPrecipChance,
            storedNightPrecipChance = actual?.forecastNightPrecipChance,
            hourlySummary = weather?.hourlySummary,
        )
    }

    /**
     * The shared daily icon rule ([com.weatherwidget.shared.util.DailyIconName], also the desktop
     * header's no-hourly fallback) as a drawable.
     */
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
        return WeatherIconMapper.fromIconName(
            DailyIconName.resolve(
                sourceId = weather.source,
                nativeToken = weather.nativeDailyIconToken,
                condition = weather.condition,
                precipProbability = weather.precipProbability,
                targetDate = targetDate,
                now = now,
                latitude = latitude,
                longitude = longitude,
                dayPrecipProbability = dayPrecipProbability,
                nightPrecipProbability = nightPrecipProbability,
                cloudCover = cloudCover,
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun shouldSuppressRainIcon(
        icon: Int,
        dailyPrecipProbability: Int?,
        daysFromToday: Int,
        isNight: Boolean,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
    ): Boolean {
        val name = WeatherIconMapper.iconResToName(icon) ?: return false
        return DailyIconName.shouldSuppressRainIcon(name, daysFromToday, dayPrecipProbability, nightPrecipProbability)
    }

}
