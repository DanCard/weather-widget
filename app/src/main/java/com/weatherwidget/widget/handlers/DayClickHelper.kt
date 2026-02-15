package com.weatherwidget.widget.handlers

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure decision logic for day-click behavior, extracted for testability.
 *
 * When a user taps a day in the daily forecast view:
 * - Past days always open ForecastHistoryActivity (showHistory=true)
 * - Today/future days WITH any rain indication navigate to the precipitation graph
 * - Today/future days WITHOUT rain open ForecastHistoryActivity
 *
 * Rain is indicated by either:
 * - RainAnalyzer detecting future hourly rain (>= 40% probability threshold)
 * - Daily weather data showing any precipitation probability (> 0%)
 */
object DayClickHelper {

    /**
     * Determines whether a day has any rain forecast, considering both hourly
     * analysis and daily precipitation probability.
     *
     * The widget shows daily precipProbability next to current temp (e.g., "16%").
     * When this is visible, clicking the day should navigate to the precipitation
     * graph so the user can see the hourly breakdown — even if no single hour
     * exceeds the 40% RainAnalyzer threshold.
     *
     * @param rainSummary the RainAnalyzer summary (non-null when hourly rain >= 40%)
     * @param dailyPrecipProbability the daily precipitation probability from WeatherEntity
     * @return true if any rain indication exists
     */
    fun hasRainForecast(rainSummary: String?, dailyPrecipProbability: Int?): Boolean {
        return !rainSummary.isNullOrEmpty() || (dailyPrecipProbability != null && dailyPrecipProbability > 0)
    }

    /**
     * Determines whether clicking a day should navigate to the precipitation graph.
     *
     * @param isPastDay true if the target day is before today
     * @param hasRainForecast true if any rain indication exists for this day
     * @return true if the click should switch to the hourly precipitation view
     */
    fun shouldNavigateToPrecipitation(isPastDay: Boolean, hasRainForecast: Boolean): Boolean {
        return !isPastDay && hasRainForecast
    }

    /**
     * Calculates the hourly offset for centering the precipitation graph on a target day.
     * The graph centers on 8 AM of the target day, relative to the current hour.
     *
     * @param now the current date-time (will be truncated to the hour)
     * @param targetDay the day being clicked
     * @return hours between the current hour and 8 AM on targetDay (negative if past 8 AM)
     */
    fun calculatePrecipitationOffset(now: LocalDateTime, targetDay: LocalDate): Int {
        val truncatedNow = now.truncatedTo(ChronoUnit.HOURS)
        val targetCenter = targetDay.atTime(8, 0)
        return Duration.between(truncatedNow, targetCenter).toHours().toInt()
    }
}
