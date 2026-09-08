package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.MonthDay

internal object DailyFutureDayResolver {

    data class FutureDayValues(
        val finalHigh: Float?,
        val finalLow: Float?,
        val fHigh: Float?,
        val fLow: Float?,
        val isClimateOverlay: Boolean,
    )

    fun isTerminalLowOnlyNwsFutureDay(
        weather: ForecastEntity?,
        date: LocalDate,
        today: LocalDate,
        weatherByDate: Map<LocalDate, ForecastEntity>,
    ): Boolean {
        if (weather?.source != WeatherSource.NWS.id) return false
        if (!date.isAfter(today)) return false
        if (weather.highTemp != null || weather.lowTemp == null) return false

        val lastNwsFutureDate =
            weatherByDate.entries
                .asSequence()
                .filter { (candidateDate, candidateWeather) ->
                    candidateDate.isAfter(today) && candidateWeather.source == WeatherSource.NWS.id
                }
                .map { it.key }
                .maxOrNull()

        return date == lastNwsFutureDate
    }

    fun resolveFutureDayValues(
        weather: ForecastEntity?,
        forecast: ForecastEntity?,
        date: LocalDate,
        isTerminalLowOnlyNwsFuture: Boolean,
        climateNormals: Map<MonthDay, Pair<Float, Float>>,
        showComparison: Boolean = false,
    ): FutureDayValues {
        var finalHigh: Float? = weather?.highTemp
        var finalLow: Float? = weather?.lowTemp
        var isClimateOverlay = false

        if (weather != null && !isTerminalLowOnlyNwsFuture && (finalHigh == null || finalLow == null)) {
            val normal = climateNormals[MonthDay.from(date)]
            if (normal != null) {
                finalHigh = normal.first
                finalLow = normal.second
                isClimateOverlay = true
            }
        }

        var fHigh: Float? = null
        var fLow: Float? = null
        if (showComparison) {
            fHigh = forecast?.highTemp
            fLow = forecast?.lowTemp
        }

        return FutureDayValues(
            finalHigh = finalHigh,
            finalLow = finalLow,
            fHigh = fHigh,
            fLow = fLow,
            isClimateOverlay = isClimateOverlay,
        )
    }
}
