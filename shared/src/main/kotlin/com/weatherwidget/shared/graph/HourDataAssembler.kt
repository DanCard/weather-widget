package com.weatherwidget.shared.graph

import com.weatherwidget.shared.actuals.ActualTemperatureSeriesResult
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the graph's point list ([HourData]) from a shared [ActualTemperatureSeriesResult]. The
 * series already contains BOTH top-of-hour forecast anchors AND the sub-hourly observed points, so
 * mapping every point (not just top-of-hour) is what makes the labeled actual high/low reproduce the
 * daily view's `daily_extremes` — an off-hour peak/trough lands on its own point instead of
 * collapsing onto the nearest hour.
 *
 * Both the Android widget and the desktop app call this so their `hours` list (and therefore the
 * extrema the label engine derives from it) are identical. Platform-specific decoration — weather
 * icon, sun/day-night flags, footer hour label, label cadence — is layered on via [decorate], which
 * receives the pure base point, whether it sits on a top-of-hour boundary, and its index. Desktop
 * renders the footer/curve from separate lists, so it passes the identity decorator.
 */
object HourDataAssembler {

    fun assembleHourData(
        series: ActualTemperatureSeriesResult,
        zoneId: ZoneId,
        decorate: (base: HourData, isTopHour: Boolean, index: Int) -> HourData = { base, _, _ -> base },
    ): List<HourData> {
        return series.points.mapIndexed { index, point ->
            val dateTime = Instant.ofEpochMilli(point.timeMs).atZone(zoneId).toLocalDateTime()
            val isTopHour = dateTime.minute == 0 && dateTime.second == 0
            val base = HourData(
                dateTime = dateTime,
                temperature = point.forecastTemp,
                label = "",
                isActual = point.isActual,
                actualTemperature = point.actualTemp,
                isObservedActual = point.isObservedActual,
            )
            decorate(base, isTopHour, index)
        }
    }
}
