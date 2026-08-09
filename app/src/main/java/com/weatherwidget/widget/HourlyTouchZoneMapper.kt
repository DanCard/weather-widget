package com.weatherwidget.widget

import kotlin.math.roundToInt

/** Maps hourly graph touch zones to the offset represented by each zone's center. */
object HourlyTouchZoneMapper {
    const val HOUR_ZONE_COUNT = 13

    fun zoneIndexToOffset(
        zoneIndex: Int,
        currentHourlyOffset: Int,
        zoom: ZoomWindow = ZoomStage.WIDE.window(),
    ): Int {
        val zoneSpan = HOUR_ZONE_COUNT - 1
        val perZoneHours = (zoom.backHours + zoom.forwardHours) / zoneSpan.toFloat()
        val asymmetryShift = (zoom.forwardHours - zoom.backHours) / 2f
        return currentHourlyOffset +
            (perZoneHours * (zoneIndex - zoneSpan / 2) + asymmetryShift).roundToInt()
    }
}
