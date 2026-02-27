package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.ViewMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure decision logic for day-click behavior, extracted for testability.
 *
 * When a user taps a day in the daily forecast view:
 * - Past days always open ForecastHistoryActivity (showHistory=true)
 * - Today/future days stay in the widget:
 *   - If any rain indication exists, navigate to the PRECIPITATION graph
 *   - Otherwise, navigate to the TEMPERATURE graph
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
     * @param rainSummary the RainAnalyzer summary (non-null when rain is starting after a dry gap)
     * @param dailyPrecipProbability the daily precipitation probability from ForecastEntity
     * @return true if any rain indication exists above the display threshold
     */
    fun hasRainForecast(rainSummary: String?, dailyPrecipProbability: Int?): Boolean {
        // Use 8% as the threshold for daily precipitation to avoid showing "boring"
        // flat rain graphs when the probability is low.
        // If RainAnalyzer detected a specific start time (rainSummary), always prioritize that.
        return !rainSummary.isNullOrEmpty() || (dailyPrecipProbability != null && dailyPrecipProbability > 8)
    }

    /**
     * Determines whether clicking a day should open the ForecastHistoryActivity.
     *
     * @param isPastDay true if the target day is before today
     * @return true if the click should launch the history activity
     */
    fun shouldShowHistory(isPastDay: Boolean): Boolean {
        return isPastDay
    }

    /**
     * Resolves the target ViewMode for a day click when not showing history.
     *
     * @param hasRainForecast true if any rain indication exists for this day
     * @return the resolved ViewMode (TEMPERATURE or PRECIPITATION)
     */
    fun resolveTargetViewMode(hasRainForecast: Boolean): ViewMode {
        return if (hasRainForecast) ViewMode.PRECIPITATION else ViewMode.TEMPERATURE
    }

    /**
     * Calculates the hourly offset for centering the hourly graphs on a target day.
     *
     * For TODAY:
     * Returns 0 to center the graph on the current hour.
     *
     * For FUTURE days:
     * Returns the offset required to center the graph on 8 AM of the target day.
     *
     * @param now the current date-time (will be truncated to the hour)
     * @param targetDay the day being clicked
     * @return hours between the current hour and the target center point
     */
    fun calculatePrecipitationOffset(now: LocalDateTime, targetDay: LocalDate): Int {
        val today = now.toLocalDate()
        if (targetDay == today) {
            return 0
        }

        val truncatedNow = now.truncatedTo(ChronoUnit.HOURS)
        val targetCenter = targetDay.atTime(8, 0)
        return Duration.between(truncatedNow, targetCenter).toHours().toInt()
    }
}
