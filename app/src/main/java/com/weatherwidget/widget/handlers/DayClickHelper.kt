package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.shared.util.WeatherTimeUtils
import com.weatherwidget.widget.ViewMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure decision logic for day-click behavior, extracted for testability.
 *
 * Routing and offset math delegate to [DayClickResolver] in `:shared`.
 */
object DayClickHelper {

    fun hasRainForecast(rainSummary: String?, dailyPrecipProbability: Int?): Boolean {
        return !rainSummary.isNullOrEmpty() || (dailyPrecipProbability != null && dailyPrecipProbability > 8)
    }

    fun resolveDailyTargetViewMode(iconRes: Int?, precipProbability: Int?): ViewMode =
        mapDayClickView(
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                iconRes?.let(WeatherIconMapper::iconResToName),
                precipProbability,
            ),
        )

    /**
     * The upper half of a day column, above the nav chevrons. Unconditional by design — it takes no
     * icon and no probability, so there is nothing here to drift with the forecast.
     */
    fun resolveUpperColumnTargetViewMode(): ViewMode =
        mapDayClickView(
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER,
                iconName = null,
                precipProbability = null,
            ),
        )

    fun resolveBottomRowTargetViewMode(iconRes: Int?): ViewMode =
        mapDayClickView(
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.BOTTOM_ICON,
                iconRes?.let(WeatherIconMapper::iconResToName),
                precipProbability = null,
            ),
        )

    private fun mapDayClickView(view: DayClickResolver.DayClickView): ViewMode = when (view) {
        DayClickResolver.DayClickView.PRECIPITATION -> ViewMode.PRECIPITATION
        DayClickResolver.DayClickView.CLOUD_COVER -> ViewMode.CLOUD_COVER
        DayClickResolver.DayClickView.TEMPERATURE -> ViewMode.TEMPERATURE
    }

    fun resolveHourlyBottomRowAction(
        iconRes: Int?,
        currentView: ViewMode,
    ): ViewMode? {
        if (iconRes == null) return null
        val iconHome = resolveBottomRowTargetViewMode(iconRes)
        return if (iconHome == currentView) null else iconHome
    }

    fun calculatePrecipitationOffset(now: LocalDateTime, targetDay: LocalDate): Int =
        DayClickResolver.calculateHourlyOffset(now, targetDay)

    fun calculateNightCenterOffset(
        now: LocalDateTime,
        targetDay: LocalDate,
        lat: Double,
        lon: Double,
    ): Int {
        val sunsetToday = SunPositionUtils.getSunTimes(targetDay.atStartOfDay(), lat, lon).sunsetHour
        val sunriseTomorrow =
            SunPositionUtils.getSunTimes(targetDay.plusDays(1).atStartOfDay(), lat, lon).sunriseHour
        val nightMidHourFromTargetMidnight = (sunsetToday + 24.0 + sunriseTomorrow) / 2.0
        val nightMid = targetDay.atStartOfDay()
            .plusMinutes((nightMidHourFromTargetMidnight * 60).toLong())

        val alignedNow = WeatherTimeUtils.alignToNearestHourHalfUp(now)
        return Duration.between(alignedNow, nightMid).toHours().toInt()
    }
}