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

        /** A past day built from the NWS station pull: the stored pool is thinner than the pull. */
        PULL_FROZEN,

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
     * @param source the row's source id; its readings are the actuals provider's
     *   ([ActualsProviderResolver.providerIdFor]), which for a borrower is another feed.
     * @param readings any pool; scoped here to the provider and to [date].
     */
    fun keep(
        date: LocalDate,
        today: LocalDate,
        source: String,
        storedActualsSource: String?,
        storedHigh: Float?,
        storedLow: Float?,
        readings: List<ObservationReading>,
        zone: ZoneId,
    ): Keep {
        if (!date.isBefore(today)) return Keep.NONE
        if (DailyActualsSource.fromStored(storedActualsSource) == DailyActualsSource.NWS_STATION_PULL) {
            return Keep.PULL_FROZEN
        }
        if (storedHigh == null || storedLow == null) return Keep.NONE
        val providerId = WeatherSource.entries.firstOrNull { it.id == source }
            ?.let { ActualsProviderResolver.providerIdFor(it) }
            ?: source
        val providerReadings = readings.filter { it.api == providerId && it.stationId != NwsBlend.STATION_ID }
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return if (StationDailyExtremes.poolCoversDay(providerReadings, dayStartMs, dayEndMs, zone)) {
            Keep.NONE
        } else {
            Keep.UNCOVERED
        }
    }
}
