package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.actuals.HourlyForecastSelector
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The header's weather icon, one rule for Android and desktop: the displayed source's hourly
 * forecast row for the coming hour (daily view) or the current hour (hourly views), drawn with its
 * cloud cover, rain chance and day/night.
 *
 * Never a station's text condition: an automated (AUTO) METAR reports `CLR` whenever nothing is below
 * ~12,000 ft, so a high overcast read "Clear" and desktop drew a sun under 99 % cloud (2026-10-21,
 * KNUQ; `plans/261021-desktop-header-icon-from-shared-current-hour-rule.md`).
 */
object HeaderConditionIcon {

    /**
     * Hour keys to try, in order. The daily header shows the coming hour first (still today), then
     * the current one; hourly headers show the current hour. Keys align half-up to the nearest hour,
     * like the stored rows' lookup on both platforms.
     */
    fun candidateHourKeys(
        now: LocalDateTime,
        preferNextHour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        val times = buildList {
            if (preferNextHour) now.plusHours(1).takeIf { it.toLocalDate() == now.toLocalDate() }?.let(::add)
            add(now)
        }
        return times.map { WeatherTimeUtils.alignToNearestHourHalfUp(it).atZone(zone).toInstant().toEpochMilli() }
    }

    /** The displayed source's row for the first of [candidateHourKeys] it has, at the newest fetch's site. */
    fun pickRow(
        hourly: List<HourlyForecast>,
        displaySourceId: String,
        now: LocalDateTime,
        preferNextHour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): HourlyForecast? {
        val anchor = hourly.maxByOrNull { it.fetchedAt } ?: return null
        val byTime = HourlyForecastSelector.selectForecastsByTime(
            rows = hourly,
            displaySourceId = displaySourceId,
            centerLat = anchor.locationLat ?: Double.NaN,
            centerLon = anchor.locationLon ?: Double.NaN,
        )
        return candidateHourKeys(now, preferNextHour, zone).firstNotNullOfOrNull { byTime[it] }
    }

    /**
     * Icon name for [row]; night from the sun at [now] (unknown location → day). Hourly headers also
     * draw twilight and the sunrise/sunset hour ([withTwilight]); the daily header does not.
     */
    fun iconName(row: HourlyForecast, now: LocalDateTime, lat: Double?, lon: Double?, withTwilight: Boolean = false): String {
        val sun = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon)
        return WeatherConditionResolver.resolveIconName(
            condition = row.condition,
            isNight = sun.isNight,
            cloudCover = row.cloudCover,
            precipProbability = row.precipProbability,
            isTwilight = withTwilight && sun.phase == SunPhase.TWILIGHT,
            isSunBoundary = withTwilight && sun.isSunBoundary,
        )
    }

    fun iconName(condition: String?, cloudCover: Int?, precipProbability: Int?, isNight: Boolean): String =
        WeatherConditionResolver.resolveIconName(
            condition = condition,
            isNight = isNight,
            cloudCover = cloudCover,
            precipProbability = precipProbability,
        )
}
