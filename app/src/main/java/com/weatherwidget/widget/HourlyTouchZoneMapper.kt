package com.weatherwidget.widget

import com.weatherwidget.shared.graph.DrawnTimeAxis
import com.weatherwidget.shared.graph.HourlyZoomRules

/** Maps hourly graph touch zones to the offset represented by each zone's center. */
object HourlyTouchZoneMapper {
    const val HOUR_ZONE_COUNT = 13

    fun zoneIndexToOffset(
        zoneIndex: Int,
        currentHourlyOffset: Int,
        zoom: ZoomWindow = ZoomStage.WIDE.window(),
        drawnAxis: DrawnTimeAxis? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val fraction = zoneIndex / (HOUR_ZONE_COUNT - 1).toDouble()
        if (drawnAxis != null) return drawnAxis.tapOffsetHours(fraction, nowMs)
        return HourlyZoomRules.tapOffsetHours(
            fraction = fraction,
            startOffsetHours = (currentHourlyOffset - zoom.backHours).toDouble(),
            spanHours = (zoom.backHours + zoom.forwardHours).toDouble(),
        )
    }
}
