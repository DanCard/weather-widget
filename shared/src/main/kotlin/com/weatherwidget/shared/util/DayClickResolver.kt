package com.weatherwidget.shared.util

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Platform-neutral daily day-click routing and hourly offset, shared by Android and desktop.
 */
object DayClickResolver {

    enum class DayTapZone {
        /** Main column body (temp bars / high-low area). */
        MAIN_COLUMN,
        /** Bottom icon band (weather icon + low label row). */
        BOTTOM_ICON,
    }

    enum class DayClickView {
        TEMPERATURE,
        PRECIPITATION,
        CLOUD_COVER,
    }

    fun resolveView(zone: DayTapZone, iconName: String?, precipProbability: Int?): DayClickView {
        if (iconName == null) return DayClickView.TEMPERATURE
        return when (zone) {
            DayTapZone.MAIN_COLUMN -> {
                if (WeatherConditionResolver.shouldDailyClickShowPrecip(
                        WeatherConditionResolver.isRainIndicator(iconName),
                        precipProbability,
                    )
                ) {
                    DayClickView.PRECIPITATION
                } else {
                    DayClickView.TEMPERATURE
                }
            }
            DayTapZone.BOTTOM_ICON -> when (WeatherConditionResolver.resolveIconHome(iconName)) {
                WeatherConditionResolver.IconHome.PRECIPITATION -> DayClickView.PRECIPITATION
                WeatherConditionResolver.IconHome.CLOUD_COVER -> DayClickView.CLOUD_COVER
                WeatherConditionResolver.IconHome.HOURLY -> DayClickView.TEMPERATURE
            }
        }
    }

    /**
     * Hours from [now] (hour-aligned) to the hourly-graph center for [targetDay].
     * Today returns 0; other days anchor around noon of the target day.
     *
     * For asymmetric windows like [ZoomStage.WIDE] (12h back / 6h forward), the visual center of
     * [centerTime - backHours, centerTime + forwardHours] is centered on noon when centerTime is
     * shifted by (backHours - forwardHours) / 2 (+3h -> 15:00), framing 3am..9pm with noon at 50%.
     */
    fun calculateHourlyOffset(
        now: LocalDateTime,
        targetDay: LocalDate,
        zoomStage: com.weatherwidget.shared.graph.ZoomStage = com.weatherwidget.shared.graph.ZoomStage.WIDE,
    ): Int {
        if (targetDay == now.toLocalDate()) return 0
        val alignedNow = WeatherTimeUtils.alignToNearestHourHalfUp(now)
        val window = zoomStage.window()
        val shiftHours = (window.backHours - window.forwardHours) / 2
        val targetCenter = targetDay.atTime(12, 0).plusHours(shiftHours)
        return Duration.between(alignedNow, targetCenter).toHours().toInt()
    }
}