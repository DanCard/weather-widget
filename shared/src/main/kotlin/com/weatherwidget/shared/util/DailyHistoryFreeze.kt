package com.weatherwidget.shared.util

import java.time.LocalDate
import java.time.ZoneId

/**
 * Freeze-window gating and value merging for the archival forecast-display columns on
 * daily_history ([com.weatherwidget.data.model.DailyHistory.forecastHighTemp] / forecastLowTemp /
 * forecastPrecipAmountMm / noonCloudPercent). Shared by the Android and desktop writers so both
 * freeze identical values under identical rules.
 *
 * These columns replay "what the daily bar view displayed" for a day after it rolls into history,
 * making the row self-sufficient — the forecasts and hourly tables it would otherwise need can age
 * out on their own retention. Like the rain-chance snapshot columns, a value is only (re)written
 * while its window is open; recomputing later from the live (REPLACE-overwritten) tables would
 * hindcast-drift the archive.
 */
object DailyHistoryFreeze {

    /**
     * Forecast-overlay freeze window (forecastHighTemp/LowTemp/PrecipAmountMm): open until local
     * midnight at the end of [date]. The past-day overlay means "the most recent complete forecast
     * while the day was live", so nothing fetched after the day ends may alter it.
     */
    fun overlayWindowOpen(nowMs: Long, date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Boolean =
        nowMs < date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    /**
     * Noon-cloud freeze window: open until 8am the day after [date] (matching the night rain-chance
     * window close). Noon cloud comes from measured/latest hourly data, which can still be backfilled
     * shortly after midnight — closing at midnight would freeze a gap that the morning fetch fills.
     */
    fun noonCloudWindowOpen(nowMs: Long, date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Boolean =
        nowMs < date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()

    data class FrozenDisplay(
        val forecastHighTemp: Float?,
        val forecastLowTemp: Float?,
        val forecastPrecipAmountMm: Float?,
        val noonCloudPercent: Int?,
    )

    /**
     * Merges freshly resolved display values into the existing frozen ones. Monotone: a frozen
     * column only moves null→value or value→newer value while its window is open, never back to
     * null — a fetch gap, an incomplete evening batch (NWS drops lowTemp once the low has passed),
     * or a missing noon reading must not erase an archived value. The overlay high/low move as a
     * unit so the replayed yellow bar can never mix two batches; because every fetch re-merges,
     * the surviving pair is the day's most recent complete batch — the same one the snapshot-table
     * reader would select.
     */
    fun merge(
        overlayOpen: Boolean,
        noonCloudOpen: Boolean,
        resolvedHigh: Float?,
        resolvedLow: Float?,
        resolvedPrecipAmountMm: Float?,
        resolvedNoonCloudPercent: Int?,
        existing: FrozenDisplay,
    ): FrozenDisplay {
        val overlayComplete = overlayOpen && resolvedHigh != null && resolvedLow != null
        return FrozenDisplay(
            forecastHighTemp = if (overlayComplete) resolvedHigh else existing.forecastHighTemp,
            forecastLowTemp = if (overlayComplete) resolvedLow else existing.forecastLowTemp,
            forecastPrecipAmountMm = if (overlayComplete) {
                resolvedPrecipAmountMm ?: existing.forecastPrecipAmountMm
            } else {
                existing.forecastPrecipAmountMm
            },
            noonCloudPercent = if (noonCloudOpen) {
                resolvedNoonCloudPercent ?: existing.noonCloudPercent
            } else {
                existing.noonCloudPercent
            },
        )
    }
}
