package com.weatherwidget.shared.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Same-day high is only a prediction until a fixed local cutoff. After that a fetch's high is a
 * hindcast and must not enter `forecasts` (user rule, 2026-10-04): the high after 16:00.
 *
 * Today's low is never frozen (user, 2026-11-06): every fetch writes it, so the daily view's
 * forecast low matches the newest hourly forecast. [LOW_CUTOFF] remains the D−1 anchor for
 * [PriorDayForecast] and the History of Forecasts hindcast trim. See
 * plans/261106-remove-same-day-low-freeze.md.
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
    )

    /**
     * Nulls out a same-day high past its cutoff at [nowMs]; the low always passes through. Future
     * dates pass through; past dates are already dropped by the writers.
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
            return Filtered(highTemp, lowTemp, frozeHigh = false)
        }
        val nowTime = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val freezeHigh = !nowTime.isBefore(HIGH_CUTOFF)
        return Filtered(
            highTemp = if (freezeHigh) null else highTemp,
            lowTemp = lowTemp,
            frozeHigh = freezeHigh && highTemp != null,
        )
    }

    /**
     * Today's low when a fetch sends none: the stored one, if fetched within
     * [PartialForecastDays.COMPLETE_REPLACEMENT_MAX_AGE_MS]. The night that ends this morning began
     * yesterday, so a source may simply not report it any more — Google's 07:00→07:00 day files it
     * under yesterday (`GoogleWeatherApi.lowsFiledUnderTheirMorning`), and NWS drops "Tonight" in the
     * evening. Writing null made the reader swap in an older complete row, high included; keeping the
     * low leaves this fetch's high standing. Null for any other day.
     * `plans/261010-google-daily-low-filed-under-the-morning-it-ends.md`
     */
    fun keptTodayLow(isToday: Boolean, priorLow: Float?, priorFetchedAt: Long?, nowMs: Long): Float? =
        priorLow.takeIf {
            isToday && priorFetchedAt != null && nowMs - priorFetchedAt <= PartialForecastDays.COMPLETE_REPLACEMENT_MAX_AGE_MS
        }
}
