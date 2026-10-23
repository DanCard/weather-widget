package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PartialForecastDays
import java.time.LocalDate

internal object DailyFutureDayResolver {

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
}
