package com.weatherwidget.widget.handlers

import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Logic shared verbatim by [CloudCoverViewHandler] and [PrecipViewHandler] — the two hourly %-graph
 * widget binders are near-identical siblings. Only the per-graph value (cloud cover vs rain chance)
 * and each handler's own data-class construction differ, so those stay at the call sites; everything
 * here was byte-for-byte duplicated.
 */
internal object HourlyGraphViewCommon {

    /** Four related view IDs for one text-mode column (label / icon / high / low slots). */
    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * Binds the text-mode (non-graph) column layout: groups forecasts by hour (preferred source →
     * GENERIC_GAP → first), picks the per-column hour offsets for [numColumns], and fills the six
     * day containers. [valueText] supplies the per-graph cell string (e.g. "60%" / "--%") for the
     * present-or-absent forecast at each column. Identical in both handlers apart from that string.
     */
    fun bindHourlyTextMode(
        views: RemoteViews,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        valueText: (forecast: HourlyForecastEntity?) -> String,
    ) {
        val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
            .mapValues { entry ->
                entry.value.find { it.source == displaySource.id }
            }

        val timeOffsets = when {
            numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
            numColumns == 5 -> listOf(0, 3, 6, 9, 12)
            numColumns == 4 -> listOf(0, 3, 6, 9)
            numColumns == 3 -> listOf(0, 3, 6)
            numColumns == 2 -> listOf(0, 6)
            else -> listOf(0)
        }

        val containerIds = listOf(
            R.id.day1_container to Quad(R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low),
            R.id.day2_container to Quad(R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low),
            R.id.day3_container to Quad(R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low),
            R.id.day4_container to Quad(R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low),
            R.id.day5_container to Quad(R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low),
            R.id.day6_container to Quad(R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low),
        )

        val zoneId = ZoneId.systemDefault()
        containerIds.forEachIndexed { index, (containerId, ids) ->
            if (index < timeOffsets.size) {
                val offset = timeOffsets[index]
                val targetTime = centerTime.plusHours(offset.toLong())
                val hourMs = targetTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                    .atZone(zoneId).toInstant().toEpochMilli()
                val forecast = forecastsByTime[hourMs]

                views.setViewVisibility(containerId, View.VISIBLE)
                val label = if (offset == 0) "Now" else "+${offset}h"
                views.setTextViewText(ids.first, label)
                views.setViewVisibility(ids.second, View.GONE)
                views.setTextViewText(ids.third, valueText(forecast))
                views.setTextViewText(ids.fourth, "")
            } else {
                views.setViewVisibility(containerId, View.GONE)
            }
        }
    }

    /** Per-hour presentation derived identically by both graph hour-data builders. */
    data class HourPresentation(
        val isCurrentHour: Boolean,
        val showLabel: Boolean,
        val isNight: Boolean,
        val isTwilight: Boolean,
        val isSunBoundary: Boolean,
        val iconRes: Int,
        val isSunny: Boolean,
        val isRainy: Boolean,
        val isMixed: Boolean,
        val label: String,
        val isDateLabel: Boolean,
    )

    /** The footer-label decision for one hour: which text, whether to show it, and which kind. */
    data class HourLabelInfo(val label: String, val showLabel: Boolean, val isDateLabel: Boolean)

    /**
     * The footer-label rule shared by all three hourly graphs (temperature, precip, cloud). At
     * multi-day ([dateMode], i.e. THREE_DAY zoom) the footer shows one date label per day ("Tue 23")
     * at the per-day representative hours in [dateLabelMillis]; otherwise it shows a time-of-day label
     * ("3p") governed by each graph's own cadence ([nonDateShowLabel]). [isDateLabel] downstream
     * drives both the drop-when-clipped and keep-the-last-day-icon behavior in
     * [com.weatherwidget.widget.HourlyFooterRenderer.drawHourLabels].
     */
    fun resolveHourLabel(
        time: LocalDateTime,
        hourMs: Long,
        dateMode: Boolean,
        dateLabelMillis: Set<Long>,
        nonDateShowLabel: Boolean,
    ): HourLabelInfo =
        if (dateMode) {
            HourLabelInfo(formatDateLabel(time.toLocalDate()), hourMs in dateLabelMillis, true)
        } else {
            HourLabelInfo(formatHourLabel(time), nonDateShowLabel, false)
        }

    /**
     * Resolves the sun phase, weather icon, and footer label for one hour — the block both hour-data
     * builders shared verbatim. Each builder keeps its own null-check and data-class construction
     * (cloud cover vs precip probability), reading the common fields off the returned value. The
     * footer label uses the shared [resolveHourLabel] rule so all three graphs agree on date vs
     * time-of-day labels (see also the temperature graph's TemperatureHourDataBuilder).
     */
    fun resolveHourPresentation(
        currentHour: LocalDateTime,
        forecast: HourlyForecastEntity,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        labelInterval: Int,
        hourIndex: Int,
        hourMs: Long = 0L,
        dateMode: Boolean = false,
        dateLabelMillis: Set<Long> = emptySet(),
    ): HourPresentation {
        val diffMinutes = java.time.Duration.between(currentHour, now).toMinutes()
        val isClosest = kotlin.math.abs(diffMinutes) <= 30
        val labelInfo = resolveHourLabel(
            time = currentHour,
            hourMs = hourMs,
            dateMode = dateMode,
            dateLabelMillis = dateLabelMillis,
            nonDateShowLabel = isClosest || (hourIndex % labelInterval == 0),
        )
        val sunInfo = SunPositionUtils.getSunInfo(currentHour, lat, lon)
        val isNight = sunInfo.isNight
        val isTwilight = sunInfo.phase == SunPhase.TWILIGHT
        val isSunBoundary = sunInfo.isSunBoundary
        val iconRes = WeatherIconMapper.getIconResource(
            condition = forecast.condition,
            isNight = isNight,
            cloudCover = forecast.cloudCover,
            precipProbability = forecast.precipProbability,
            isTwilight = isTwilight,
            isSunBoundary = isSunBoundary,
        )
        return HourPresentation(
            isCurrentHour = isClosest,
            showLabel = labelInfo.showLabel,
            isNight = isNight,
            isTwilight = isTwilight,
            isSunBoundary = isSunBoundary,
            iconRes = iconRes,
            isSunny = WeatherIconMapper.isSunny(iconRes),
            isRainy = WeatherIconMapper.isPrecipitation(iconRes),
            isMixed = WeatherIconMapper.isMixed(iconRes),
            label = labelInfo.label,
            isDateLabel = labelInfo.isDateLabel,
        )
    }
}
