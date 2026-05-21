package com.weatherwidget.util

import android.util.Log
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
        val solidLineHigh: Float?,
        val solidLineLow: Float?,
        val dashedLineHigh: Float?,
        val dashedLineLow: Float?,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val ghostLineHigh: Float? = null,
        val snapshotIconRes: Int? = null,
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
        snapshotIconRes: Int? = null,
    ): TodayTripleLineValues {
        val zoneId = ZoneId.systemDefault()
        // Filter all hourly data for today
        val todayHourly = hourlyForecasts.filter {
            Instant.ofEpochMilli(it.dateTime).atZone(zoneId).toLocalDate() == today &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        // 1. Observed so far (history/current)
        val actual = dailyActuals[today]
        // solidLineHigh represents the current 'mercury level'. 
        // It shows the real-time temp if available, otherwise falls back to the peak reached so far.
        val solidLineHigh = currentTemp ?: actual?.highTemp
        
        // ghostLineHigh represents the faint high-water mark peak reached so far today.
        val ghostLineHigh = actual?.highTemp

        // solidLineLow should be the minimum of the stored daily low and the current reading.
        val solidLineLow = listOfNotNull(actual?.lowTemp, currentTemp).minOrNull()
        val solidLineHighSource =
            when {
                currentTemp != null -> "current_temp"
                actual?.highTemp != null -> "daily_actual_high"
                else -> "none"
            }
        val solidLineLowSource =
            when {
                actual?.lowTemp != null && currentTemp != null ->
                    if (actual.lowTemp <= currentTemp) "daily_actual_low" else "current_temp"
                actual?.lowTemp != null -> "daily_actual_low"
                currentTemp != null -> "current_temp"
                else -> "none"
            }

        // 2. Full-day prediction (including both past and future hours)
        val hourlyMax = todayHourly.maxOfOrNull { it.temperature }
        val hourlyMin = todayHourly.minOfOrNull { it.temperature }

        // Prefer the official daily high/low from the API for the dashed line.
        val dashedLineHigh = fallbackWeather?.highTemp ?: hourlyMax
        val dashedLineLow = listOfNotNull(
            fallbackWeather?.lowTemp,
            hourlyMin
        ).minOrNull()

        Log.d("DailyEstimator", "today: actual.high=${actual?.highTemp} actual.low=${actual?.lowTemp} currentTemp=$currentTemp " +
            "solidLineHigh=$solidLineHigh solidLineHighSource=$solidLineHighSource " +
            "solidLineLow=$solidLineLow solidLineLowSource=$solidLineLowSource " +
            "fallbackWeather.high=${fallbackWeather?.highTemp} fallbackWeather.low=${fallbackWeather?.lowTemp} " +
            "hourlyMax=$hourlyMax hourlyMin=$hourlyMin dashedLineHigh=$dashedLineHigh dashedLineLow=$dashedLineLow " +
            "todayHourlyCount=${todayHourly.size} source=${displaySource.id}")

        return TodayTripleLineValues(
            solidLineHigh = solidLineHigh,
            solidLineLow = solidLineLow,
            dashedLineHigh = dashedLineHigh,
            dashedLineLow = dashedLineLow,
            snapshotHigh = snapshotHigh,
            snapshotLow = snapshotLow,
            ghostLineHigh = ghostLineHigh,
            snapshotIconRes = snapshotIconRes,
        )
    }

    /**
     * Estimates a single high/low pair for Today, using full-day hourly data
     * (consistent with existing skip-yesterday behavior).
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
