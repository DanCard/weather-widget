package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Single source of truth (Android + desktop) for a day's representative cloud cover: the hourly
 * `cloudCover` reading closest to **noon** on [date], for the **displayed source only**.
 *
 * Source isolation is the whole point — a day shown for source X must never borrow source Y's
 * cloud reading. The only sanctioned exception is the climate-normal gap: when the day's stored
 * forecast row is [WeatherSource.GENERIC_GAP] we look at GENERIC_GAP hourly instead.
 */
object DailyNoonCloudCover {

    /** Noon cloud cover as 0–100 percent for the target source, or null if none. */
    fun resolveNoonCloudCoverPercent(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? {
        val targetSourceId = if (rowSourceId == WeatherSource.GENERIC_GAP.id) {
            WeatherSource.GENERIC_GAP.id
        } else {
            displaySourceId
        }
        val noon = date.atTime(12, 0)
        return hourly.asSequence()
            .filter { it.source == targetSourceId }
            .mapNotNull { forecast ->
                val cloud = forecast.cloudCover ?: return@mapNotNull null
                val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(forecast.dateTime), zone)
                if (local.toLocalDate() != date) return@mapNotNull null
                Triple(abs(ChronoUnit.MINUTES.between(noon, local)), local, cloud)
            }
            .minWithOrNull(compareBy<Triple<Long, LocalDateTime, Int>> { it.first }.thenBy { it.second })
            ?.third
            ?.coerceIn(0, 100)
    }

    /** Convenience: the same value as a 0.0–1.0 ratio (what the bar renderer wants). */
    fun resolveNoonCloudCoverRatio(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Float? = resolveNoonCloudCoverPercent(hourly, date, displaySourceId, rowSourceId, zone)?.div(100f)
}
