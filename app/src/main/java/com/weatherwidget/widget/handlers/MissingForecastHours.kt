package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Describes gaps in the display source's hourly forecast anchors. The graph's dashed forecast line
 * has no point for these hours, so persist the exact spans with the existing sparse gap-refresh
 * event instead of leaving a blank segment unexplained in a later log review.
 */
@VisibleForTesting
internal fun summarizeMissingForecastHours(
    startHour: LocalDateTime,
    endHour: LocalDateTime,
    zoneId: ZoneId,
    forecastsByTime: Map<Long, HourlyForecastEntity?>,
    displaySource: WeatherSource,
): MissingForecastHours {
    val missingHours = mutableListOf<LocalDateTime>()
    var noSelectedForecastCount = 0
    var wrongSourceCount = 0
    var current = startHour

    // End-inclusive, matching the hours the graph draws (ActualTemperatureSeriesBuilder): the last
    // mark is part of the view, so a gap there has to be reported like any other.
    while (!current.isAfter(endHour)) {
        val hourMs = current.atZone(zoneId).toInstant().toEpochMilli()
        val selected = forecastsByTime[hourMs]
        when {
            selected == null -> {
                noSelectedForecastCount++
                missingHours += current
            }
            selected.source != displaySource.id -> {
                wrongSourceCount++
                missingHours += current
            }
        }
        current = current.plusHours(1)
    }

    val spans = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    missingHours.forEach { hour ->
        val previous = spans.lastOrNull()
        if (previous != null && previous.second == hour) {
            spans[spans.lastIndex] = previous.first to hour.plusHours(1)
        } else {
            spans += hour to hour.plusHours(1)
        }
    }
    return MissingForecastHours(
        missingCount = missingHours.size,
        noSelectedForecastCount = noSelectedForecastCount,
        wrongSourceCount = wrongSourceCount,
        spans = spans,
    )
}
