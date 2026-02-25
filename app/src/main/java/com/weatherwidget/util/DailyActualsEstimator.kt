package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Utility to estimate observed vs. forecasted temperature ranges for a day.
 */
object DailyActualsEstimator {

    /**
     * The hour (24h format) when the daily high temperature has typically occurred.
     * Before this hour, the "today" column's observed high (Yellow/Orange bars)
     * will remain at the forecasted high. After this hour, it will reflect the
     * actual maximum temperature observed so far.
     */
    private const val TYPICAL_HIGH_HOUR = 16

    /**
     * Values for rendering the "Today" triple-line representation.
     */
    data class TodayTripleLineValues(
        val observedHigh: Int?,
        val observedLow: Int?,
        val forecastHigh: Int?,
        val forecastLow: Int?
    )

    /**
     * Calculates the "observed so far" and "full-day prediction" ranges for today.
     * To be battery-aware, it reuses the provided hourlyForecasts and uses a single
     * current time reference.
     *
     * @param hourlyForecasts Full list of hourly forecasts already in memory.
     * @param today The current local date.
     * @param now The current local date-time (for filtering "so far").
     * @param displaySource The primary weather source for this widget.
     * @param fallbackWeather The daily weather entity to use if hourly data is missing.
     */
    fun calculateTodayTripleLineValues(
        hourlyForecasts: List<HourlyForecastEntity>,
        today: LocalDate,
        now: LocalDateTime,
        displaySource: WeatherSource,
        fallbackWeather: WeatherEntity
    ): TodayTripleLineValues {
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        // Filter all hourly data for today
        val todayHourly = hourlyForecasts.filter {
            it.dateTime.startsWith(todayStr) &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        if (todayHourly.isEmpty()) {
            return TodayTripleLineValues(
                observedHigh = fallbackWeather.highTemp,
                observedLow = fallbackWeather.lowTemp,
                forecastHigh = fallbackWeather.highTemp,
                forecastLow = fallbackWeather.lowTemp
            )
        }

        // 1. Observed so far (history/current)
        val observedToday = todayHourly.filter { it.dateTime <= nowStr }
        val actualHighSoFar = observedToday.maxOfOrNull { it.temperature }?.toInt()
        val actualLowSoFar = observedToday.minOfOrNull { it.temperature }?.toInt()

        // 2. Full-day prediction (including both past and future hours)
        val forecastHigh = todayHourly.maxOfOrNull { it.temperature }?.toInt()
        val forecastLow = todayHourly.minOfOrNull { it.temperature }?.toInt()

        // Before the typical high hour (4 PM), keep the top of the bar at the forecast high.
        // After the typical high hour, update to the actual peak observed today.
        val finalObservedHigh = if (now.hour < TYPICAL_HIGH_HOUR) {
            forecastHigh
        } else {
            actualHighSoFar
        }

        return TodayTripleLineValues(
            observedHigh = finalObservedHigh ?: forecastHigh,
            observedLow = actualLowSoFar ?: forecastLow,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow
        )
    }

    /**
     * Estimates a single high/low pair for Today, using full-day hourly data
     * (consistent with existing behavior in evening mode).
     */
    fun estimateTodayActualsFromHourly(
        hourlyForecasts: List<HourlyForecastEntity>,
        today: LocalDate,
        displaySource: WeatherSource,
        fallbackWeather: WeatherEntity
    ): Pair<Int?, Int?> {
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val todayHourly = hourlyForecasts.filter {
            it.dateTime.startsWith(todayStr) &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        if (todayHourly.isEmpty()) {
            return fallbackWeather.highTemp to fallbackWeather.lowTemp
        }

        val temps = todayHourly.map { it.temperature }
        if (temps.isEmpty()) {
            return fallbackWeather.highTemp to fallbackWeather.lowTemp
        }

        return temps.maxOrNull()?.toInt() to temps.minOrNull()?.toInt()
    }
}
