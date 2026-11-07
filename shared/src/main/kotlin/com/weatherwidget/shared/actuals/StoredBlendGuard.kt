package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.NwsBlend
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whether a blend recompute keeps a stored `daily_history` row's high/low instead of writing its
 * own. One rule for Android's `DailyActualsStore.persistExtremes` and desktop's
 * `recomputeDailyExtremes`, which each carried a copy of the first reason.
 *
 * Only the temperatures (and their times) are kept; precip and condition are the recompute's as
 * before. A row with nothing stored is always written.
 */
object StoredBlendGuard {

    enum class Keep {
        /** Write the recompute's high/low. */
        NONE,

        /**
         * A past day whose provider readings miss the pre-dawn or the afternoon window
         * ([StationDailyExtremes.poolCoversDay]): a sliver of the day must not replace a whole one.
         * Fold-4 2026-10-21: Silurian's actuals switched to Synoptic, whose first 24 h fetch held
         * only 22:43–23:55 of the day before; the recompute wrote 61.9/60.4 over a stored 68.3/51.0
         * (plans/261022-past-day-blend-needs-whole-day-coverage.md).
         */
        UNCOVERED,
    }

    /**
     * A past day's stored row was once kept whenever the NWS station pull had written it
     * (`PULL_FROZEN`). Removed 2026-11-07: the pull's blend then outlived the stored pool's, so the
     * daily view showed 73.4° for a day the hourly graph drew peaking at 71.5°. The pull had missed
     * KPAO's 12:47 and 13:47 reports (api.weather.gov dropped them; the METARs read 18-19 °C), so
     * the stored pool was the fuller one. Coverage is now the only reason to keep a stored row.
     *
     * @param source the row's source id; its readings are the actuals provider's
     *   ([ActualsProviderResolver.providerIdFor]), which for a borrower is another feed.
     * @param readings any pool; scoped here to the provider and to [date].
     */
    fun keep(
        date: LocalDate,
        today: LocalDate,
        source: String,
        storedHigh: Float?,
        storedLow: Float?,
        readings: List<ObservationReading>,
        zone: ZoneId,
    ): Keep {
        if (!date.isBefore(today)) return Keep.NONE
        if (storedHigh == null || storedLow == null) return Keep.NONE
        return if (storedPoolCoversDay(date, source, readings, zone)) Keep.NONE else Keep.UNCOVERED
    }

    /**
     * Whether [source]'s actuals provider has readings across [date]'s pre-dawn and afternoon
     * windows ([StationDailyExtremes.poolCoversDay]). When it does, the blend recompute owns the
     * day's high/low — the same pool the hourly graph draws — and no other writer may replace it.
     */
    fun storedPoolCoversDay(
        date: LocalDate,
        source: String,
        readings: List<ObservationReading>,
        zone: ZoneId,
    ): Boolean {
        val providerId = WeatherSource.entries.firstOrNull { it.id == source }
            ?.let { ActualsProviderResolver.providerIdFor(it) }
            ?: source
        val providerReadings = readings.filter { it.api == providerId && it.stationId != NwsBlend.STATION_ID }
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return StationDailyExtremes.poolCoversDay(providerReadings, dayStartMs, dayEndMs, zone)
    }
}
