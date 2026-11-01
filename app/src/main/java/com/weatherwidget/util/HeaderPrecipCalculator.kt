package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
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
}
