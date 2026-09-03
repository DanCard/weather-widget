package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.shared.util.DayClickResolver
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The precipitation figure a desktop day tap is gated on, matching Android's
 * `DailyClickHandlerFactory` through the shared [DayClickResolver].
 */
internal fun dayClickRoutingPrecip(
    config: DesktopConfig,
    clickedDate: LocalDate,
    days: List<DesktopDailyDay>,
    now: LocalDateTime,
    hourly: List<HourlyForecast>,
): DayClickResolver.RoutingPrecip {
    val clickedDay = days.find { it.date == clickedDate }
    return DayClickResolver.routingPrecipProbability(
        targetDay = clickedDate,
        now = now,
        hourly = hourly,
        displaySourceId = config.settings.weatherSource,
        fallbackSourceId = WeatherSource.GENERIC_GAP.id,
        dailyProbability = clickedDay?.forecast?.precipProbability
            ?: clickedDay?.snapshot?.precipProbability,
    )
}

/** Desktop config for opening an hourly view focused on [clickedDate]. */
internal fun dayClickConfig(
    config: DesktopConfig,
    clickedDate: LocalDate,
    days: List<DesktopDailyDay>,
    zone: DayClickResolver.DayTapZone = DayClickResolver.DayTapZone.MAIN_COLUMN,
    now: LocalDateTime = LocalDateTime.now(),
    hourly: List<HourlyForecast> = emptyList(),
): DesktopConfig {
    val clickedDay = days.find { it.date == clickedDate }
    val precipProb = dayClickRoutingPrecip(config, clickedDate, days, now, hourly).probability
    val targetView = when (DayClickResolver.resolveView(zone, clickedDay?.iconName, precipProb)) {
        DayClickResolver.DayClickView.PRECIPITATION -> ViewMode.PRECIPITATION
        DayClickResolver.DayClickView.CLOUD_COVER -> ViewMode.CLOUD_COVER
        DayClickResolver.DayClickView.TEMPERATURE -> ViewMode.HOURLY
    }
    return config.copy(
        viewMode = targetView,
        hourlyOffset = DayClickResolver.calculateHourlyOffset(now, clickedDate),
        zoomFactor = if (config.viewMode == ViewMode.DAILY) {
            DesktopGraphUtils.zoomFactorForStage(ZoomStage.WIDE)
        } else {
            config.zoomFactor
        },
    )
}
