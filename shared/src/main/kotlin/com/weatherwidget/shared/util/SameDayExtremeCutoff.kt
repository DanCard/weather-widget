package com.weatherwidget.shared.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Same-day high/low are only predictions until a fixed local cutoff. After that a fetch's values
 * are hindcasts and must not enter `forecasts` (user rule, 2026-10-04): the low after 06:00 and the
 * high after 16:00. Per field — a post-06:00 batch may still revise the high.
 *
 * Wired from both daily writers (`ForecastSnapshotStore.saveForecastSnapshot` on Android,
 * `DesktopWeatherRepository.persistForecastResult` → `upsertForecasts` on desktop). Display reads
 * whatever the DB holds; freezing writes is enough.
 */
object SameDayExtremeCutoff {
    val LOW_CUTOFF: LocalTime = LocalTime.of(6, 0)
    val HIGH_CUTOFF: LocalTime = LocalTime.of(16, 0)

    data class Filtered(
        val highTemp: Float?,
        val lowTemp: Float?,
        val frozeHigh: Boolean,
        val frozeLow: Boolean,
    ) {
        val frozeAny: Boolean get() = frozeHigh || frozeLow
    }

    /**
     * Nulls out same-day high/low that are past their cutoff at [nowMs]. Future dates pass
     * through; past dates are already dropped by the writers.
     */
    fun filter(
        targetDate: LocalDate,
        highTemp: Float?,
        lowTemp: Float?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Filtered {
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (!targetDate.isEqual(nowDate)) {
            return Filtered(highTemp, lowTemp, frozeHigh = false, frozeLow = false)
        }
        val nowTime = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val freezeLow = !nowTime.isBefore(LOW_CUTOFF)
        val freezeHigh = !nowTime.isBefore(HIGH_CUTOFF)
        return Filtered(
            highTemp = if (freezeHigh) null else highTemp,
            lowTemp = if (freezeLow) null else lowTemp,
            frozeHigh = freezeHigh && highTemp != null,
            frozeLow = freezeLow && lowTemp != null,
        )
    }
}
