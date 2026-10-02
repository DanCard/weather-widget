package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PartialForecastDays
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
        val lastNwsFutureDate = weatherByDate.entries
            .filter { (candidateDate, candidateWeather) ->
                candidateDate.isAfter(today) && candidateWeather.source == WeatherSource.NWS.id
            }
            .maxOfOrNull { it.key }
        return PartialForecastDays.isTerminalLowOnlyNwsFutureDay(
            weather?.source, weather?.highTemp, weather?.lowTemp, date, today, lastNwsFutureDate,
        )
    }

    fun resolveFutureDayValues(
        weather: ForecastEntity?,
        forecast: ForecastEntity?,
        date: LocalDate,
        isTerminalLowOnlyNwsFuture: Boolean,
        climateNormals: Map<MonthDay, Pair<Float, Float>>,
        showComparison: Boolean = false,
    ): FutureDayValues {
        // Shared with desktop. Only an existing partial row is filled; no row stays empty.
        val filled = if (weather != null) {
            PartialForecastDays.futureFromNormals(
                weather.highTemp, weather.lowTemp, isTerminalLowOnlyNwsFuture, climateNormals[MonthDay.from(date)],
            )
        } else {
            PartialForecastDays.FutureValues(null, null, isClimateOverlay = false)
        }
        val finalHigh = filled.high
        val finalLow = filled.low
        val isClimateOverlay = filled.isClimateOverlay

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
