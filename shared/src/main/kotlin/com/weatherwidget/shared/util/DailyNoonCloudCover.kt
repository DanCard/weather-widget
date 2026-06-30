package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Single source of truth (Android + desktop) for a day's representative cloud cover: the hourly
 * `cloudCover` reading at **noon** (12:00 local) on [date], for the **displayed source only**.
 *
 * When noon data is missing, assumes **0%** (clear) rather than borrowing a non-noon hour or
 * another API source.
 *
 * The only sanctioned source exception is the climate-normal gap: when the day's stored
 * forecast row is [WeatherSource.GENERIC_GAP] we look at GENERIC_GAP hourly instead.
 */
object DailyNoonCloudCover {

    /** Noon cloud cover as 0–100 percent for the target source; 0 when noon data is missing. */
    fun resolveNoonCloudCoverPercent(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val targetSourceId = if (rowSourceId == WeatherSource.GENERIC_GAP.id) {
            WeatherSource.GENERIC_GAP.id
        } else {
            displaySourceId
        }
        val noon = date.atTime(12, 0)
        return hourly.asSequence()
            .filter { it.source == targetSourceId }
            .mapNotNull { forecast ->
                val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(forecast.dateTime), zone)
                if (local != noon) return@mapNotNull null
                forecast.cloudCover
            }
            .firstOrNull()
            ?.coerceIn(0, 100)
            ?: 0
    }

    /** Convenience: the same value as a 0.0–1.0 ratio (what the bar renderer wants). */
    fun resolveNoonCloudCoverRatio(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Float = resolveNoonCloudCoverPercent(hourly, date, displaySourceId, rowSourceId, zone) / 100f
}