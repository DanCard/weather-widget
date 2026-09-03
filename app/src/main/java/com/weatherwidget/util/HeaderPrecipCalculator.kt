package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
import com.weatherwidget.widget.handlers.HeaderConstants
import java.time.LocalDateTime

object HeaderPrecipCalculator {

    fun getNext6HourPrecipProbability(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? {
        val sharedForecasts = hourlyForecasts.map { it.toHourlyForecast() }
        return PrecipProbabilityCalculator.getNext6HourPrecipProbability(
            sharedForecasts, displaySource.id, WeatherSource.GENERIC_GAP.id, fallbackDailyProbability, referenceTime,
        )
    }

    fun resolve(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
        sunriseHour: Double,
        sunsetHour: Double,
    ): PrecipProbabilityCalculator.HeaderPrecipitation =
        PrecipProbabilityCalculator.resolveHeaderPrecipitation(
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            displaySourceId = displaySource.id,
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = fallbackDailyProbability,
            referenceTime = referenceTime,
            sunriseHour = sunriseHour,
            sunsetHour = sunsetHour,
        )

    fun getPrecipTextSize(
        precipProb: Int,
        isDailyView: Boolean = false,
        isNightPrecip: Boolean = false,
    ): Float = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * DailyRainLabels.headerPrecipFontScale(
        precipProbability = precipProb,
        isDailyView = isDailyView,
        isNightPrecip = isNightPrecip,
    )

}
