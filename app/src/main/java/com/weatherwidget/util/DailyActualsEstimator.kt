package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

import kotlin.math.roundToInt

/**
 * Utility to estimate observed vs. forecasted temperature ranges for a day.
 */
object DailyActualsEstimator {

    /**
     * Values for rendering the "Today" triple-line representation.
     */
    data class TodayTripleLineValues(
        val observedHigh: Float?,
        val observedLow: Float?,
        val forecastHigh: Float?,
        val forecastLow: Float?,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
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
     * @param currentTemp The most recently observed current temperature.
     */
    fun calculateTodayTripleLineValues(
        hourlyForecasts: List<HourlyForecastEntity>,
        today: LocalDate,
        @Suppress("UNUSED_PARAMETER") now: LocalDateTime,
        displaySource: WeatherSource,
        fallbackWeather: ForecastEntity?,
        dailyActuals: com.weatherwidget.widget.DailyActualMap = emptyMap(),
        currentTemp: Float? = null,
        snapshotHigh: Float? = null,
        snapshotLow: Float? = null,
    ): TodayTripleLineValues {
        val zoneId = ZoneId.systemDefault()
        // Filter all hourly data for today
        val todayHourly = hourlyForecasts.filter {
            Instant.ofEpochMilli(it.dateTime).atZone(zoneId).toLocalDate() == today &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        // 1. Observed so far (history/current)
        val actual = dailyActuals[today]
        val observedHigh = listOfNotNull(actual?.highTemp, currentTemp).maxOrNull()
        val observedLow = listOfNotNull(actual?.lowTemp, currentTemp).minOrNull()

        // 2. Full-day prediction (including both past and future hours)
        val hourlyMax = todayHourly.maxOfOrNull { it.temperature }
        val hourlyMin = todayHourly.minOfOrNull { it.temperature }

        // Prefer the official daily high/low from the API for the forecast line.
        val forecastHigh = fallbackWeather?.highTemp ?: hourlyMax
        val forecastLow = listOfNotNull(
            fallbackWeather?.lowTemp,
            hourlyMin
        ).minOrNull()

        return TodayTripleLineValues(
            observedHigh = observedHigh,
            observedLow = observedLow,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow,
            snapshotHigh = snapshotHigh,
            snapshotLow = snapshotLow
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
        val zoneId = ZoneId.systemDefault()
        val todayHourly = hourlyForecasts.filter {
            Instant.ofEpochMilli(it.dateTime).atZone(zoneId).toLocalDate() == today &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        if (todayHourly.isEmpty()) {
            return fallbackWeather.highTemp to fallbackWeather.lowTemp
        }

        val temps = todayHourly.map { it.temperature }
        if (temps.isEmpty()) {
            return fallbackWeather.highTemp to fallbackWeather.lowTemp
        }

        return temps.maxOfOrNull { it } to temps.minOfOrNull { it }
    }
}
