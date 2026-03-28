package com.weatherwidget.widget.handlers

import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.ViewMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import com.weatherwidget.util.WeatherTimeUtils

/**
 * Pure decision logic for day-click behavior, extracted for testability.
 *
 * Daily and hourly icon taps share the same icon-home routing:
 * - Rain/storm/snow -> precipitation graph
 * - Cloud/mixed/mostly clear -> cloud cover graph
 * - Otherwise -> temperature graph
 *
 * Legacy rain heuristics remain for display decisions only.
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

    fun resolveDailyTargetViewMode(iconRes: Int?): ViewMode = iconRes?.let(::resolveIconHome) ?: ViewMode.TEMPERATURE

    fun resolveBottomRowTargetViewMode(iconRes: Int?): ViewMode = iconRes?.let(::resolveIconHome) ?: ViewMode.TEMPERATURE

    private fun resolveIconHome(iconRes: Int): ViewMode {
        return when {
            WeatherIconMapper.isRainy(iconRes) -> ViewMode.PRECIPITATION
            WeatherIconMapper.isCloudForecastEligible(iconRes) -> ViewMode.CLOUD_COVER
            else -> ViewMode.TEMPERATURE
        }
    }

    /**
     * Resolves the action for tapping a bottom-row icon on an hourly graph.
     *
     * Each icon type has a "home" graph:
     * - Rain/storm/snow → Precipitation
     * - Cloud/mostly-clear → Cloud Cover
     * - Clear/sunny/other → Temperature
     *
     * If already on the icon's home graph, returns null (caller should zoom).
     * Otherwise returns the target ViewMode to navigate to.
     */
    fun resolveHourlyBottomRowAction(
        iconRes: Int?,
        currentView: ViewMode,
    ): ViewMode? {
        if (iconRes == null) return null
        val iconHome = resolveIconHome(iconRes)
        return if (iconHome == currentView) null else iconHome
    }

    /**
     * Calculates the hourly offset for centering the hourly graphs on a target day.
     *
     * For TODAY:
     * Returns 0 to center the graph on the current hour.
     *
     * For FUTURE days:
     * Returns the offset required to center the graph on noon of the target day.
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

        val alignedNow = WeatherTimeUtils.alignToNearestHourHalfUp(now)
        val targetCenter = targetDay.atTime(12, 0)
        return Duration.between(alignedNow, targetCenter).toHours().toInt()
    }
}
