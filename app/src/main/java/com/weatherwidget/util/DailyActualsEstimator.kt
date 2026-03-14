package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
        val observedHigh: Float?,
        val observedLow: Float?,
        val forecastHigh: Float?,
        val forecastLow: Float?
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
        fallbackWeather: ForecastEntity?,
        dailyActuals: Map<String, com.weatherwidget.widget.ObservationResolver.DailyActual> = emptyMap()
    ): TodayTripleLineValues {
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        // Filter all hourly data for today
        val todayHourly = hourlyForecasts.filter {
            it.dateTime.startsWith(todayStr) &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        // 1. Observed so far (history/current)
        // Prefer raw thermometer observations if available, as they are more reliable
        // than hourly API data which may drop past hours.
        val actual = dailyActuals[todayStr]
        val observedToday = todayHourly.filter { it.dateTime <= nowStr }
        
        val actualHighSoFar = actual?.highTemp ?: observedToday.maxOfOrNull { it.temperature }
        val actualLowSoFar = actual?.lowTemp ?: observedToday.minOfOrNull { it.temperature }

        // 2. Full-day prediction (including both past and future hours)
        val hourlyMax = todayHourly.maxOfOrNull { it.temperature }
        val hourlyMin = todayHourly.minOfOrNull { it.temperature }

        // Prefer the official daily high/low from the API for the forecast line (blue line).
        // If NWS dropped the lowTemp, fall back to MIN(hourlyMin, observedLow).
        val forecastHigh = fallbackWeather?.highTemp?.toFloat() ?: hourlyMax
        val forecastLow = listOfNotNull(
            fallbackWeather?.lowTemp?.toFloat(),
            hourlyMin,
            actualLowSoFar
        ).minOrNull()

        // Before the typical high hour (4 PM), keep the top of the bar at the expected high from hourly data.
        // This ensures we retain the tenth-of-a-digit precision from the hourly API (e.g., Open-Meteo).
        // After the typical high hour, update to the actual peak observed today.
        val finalObservedHigh = if (now.hour < TYPICAL_HIGH_HOUR) {
            hourlyMax ?: actualHighSoFar
        } else {
            actualHighSoFar
        }

        val finalObservedLow = if (displaySource == WeatherSource.NWS) {
            listOfNotNull(actualLowSoFar, forecastLow).minOrNull()
        } else {
            actualLowSoFar ?: forecastLow
        }

        return TodayTripleLineValues(
            observedHigh = finalObservedHigh ?: forecastHigh,
            observedLow = finalObservedLow,
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
        fallbackWeather: ForecastEntity
    ): Pair<Float?, Float?> {
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val todayHourly = hourlyForecasts.filter {
            it.dateTime.startsWith(todayStr) &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        if (todayHourly.isEmpty()) {
            return fallbackWeather.highTemp?.toFloat() to fallbackWeather.lowTemp?.toFloat()
        }

        val temps = todayHourly.map { it.temperature }
        if (temps.isEmpty()) {
            return fallbackWeather.highTemp?.toFloat() to fallbackWeather.lowTemp?.toFloat()
        }

        return temps.maxOfOrNull { it } to temps.minOfOrNull { it }
    }
}
