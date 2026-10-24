package com.weatherwidget.widget

import com.weatherwidget.shared.graph.HourlyZoomRules

/** Maps hourly graph touch zones to the offset represented by each zone's center. */
object HourlyTouchZoneMapper {
    const val HOUR_ZONE_COUNT = 13

    fun zoneIndexToOffset(
        zoneIndex: Int,
        currentHourlyOffset: Int,
        zoom: ZoomWindow = ZoomStage.WIDE.window(),
    ): Int = HourlyZoomRules.tapOffsetHours(
        fraction = zoneIndex / (HOUR_ZONE_COUNT - 1).toDouble(),
        startOffsetHours = (currentHourlyOffset - zoom.backHours).toDouble(),
        spanHours = (zoom.backHours + zoom.forwardHours).toDouble(),
    )
}
