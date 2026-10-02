package com.weatherwidget.util

import com.weatherwidget.shared.util.PartialForecastDays
import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyDayValueResolver
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
        /**
         * True when [solidLineLow] is a genuinely observed low from daily history.
         * False means it is a forecast stand-in — renderers must not style it as an
         * observed actual (red thermostat color).
         */
        val hasActualLow: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val ghostLineHigh: Float? = null,
        val snapshotIconRes: Int? = null,
        /** Shared thermostat bar top ([DailyDayValueResolver.TodayLineValues.barTopHigh]). */
        val barTopHigh: Float? = solidLineHigh ?: dashedLineHigh,
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

        // 1. Observed so far (history/current) and 2. full-day prediction — one shared formula
        // with desktop (DailyDayValueResolver). Hourly max/min still backs the dashed line when
        // the API daily row is missing.
        val actual = dailyActuals[today]
        val hourlyMax = todayHourly.maxOfOrNull { it.temperature }
        val hourlyMin = todayHourly.minOfOrNull { it.temperature }
        val (dashedLineHigh, dashedLineLow) = PartialForecastDays.todayForecastRange(
            fallbackWeather?.highTemp, fallbackWeather?.lowTemp, todayHourly.map { it.temperature },
        )
        val resolved = DailyDayValueResolver.resolveTodayLineValues(
            actualHigh = actual?.computedHighTemp,
            actualLow = actual?.computedLowTemp,
            forecastHigh = dashedLineHigh,
            forecastLow = dashedLineLow,
            currentTemp = currentTemp,
        )
        // solidLineHigh is the mercury (currentTemp ?: observed peak) — NOT barTopHigh. Callers
        // that draw the thermostat top use DailyTodayResolver.finalHigh / TodayLineValues.barTopHigh
        // (= solidLineHigh ?: dashedLineHigh) so the bar still rises to today's forecast high when
        // both the current reading and an observed peak are missing.
        val solidLineHigh = resolved.solidHigh
        val ghostLineHigh = resolved.ghostHigh
        val solidLineLow = resolved.solidLow
        val solidLineHighSource =
            when {
                currentTemp != null -> "current_temp"
                actual?.computedHighTemp != null -> "daily_actual_high"
                else -> "none"
            }
        val solidLineLowSource =
            when {
                actual?.computedLowTemp != null -> "daily_actual_low"
                dashedLineLow != null -> "forecast_low"
                else -> "none"
            }

        Log.d("DailyEstimator", "today: actual.high=${actual?.computedHighTemp} actual.low=${actual?.computedLowTemp} currentTemp=$currentTemp " +
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
            hasActualLow = actual?.computedLowTemp != null,
            snapshotHigh = snapshotHigh,
            snapshotLow = snapshotLow,
            ghostLineHigh = ghostLineHigh,
            snapshotIconRes = snapshotIconRes,
            barTopHigh = resolved.barTopHigh,
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
