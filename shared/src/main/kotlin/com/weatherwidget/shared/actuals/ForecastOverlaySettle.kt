package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource

/**
 * A past day's forecast overlay (the yellow bar) is the last forecast fetched **before the thing it
 * predicts happened**: the forecast high is the newest valid forecast fetched at or before the time
 * the actual high was reached, and the forecast low likewise for the low.
 *
 * User's rule, 2026-10-04: "I find it useful when forecast is updated, even an hour before the high
 * is reached. I don't find it useful when it is updated after." Fetches after an extreme are
 * hindcasts — Open-Meteo documents its past hours as "stitching the first hours of each successive
 * model run … initialised from real measurements", with nothing in the response to mark them.
 *
 * The two values may come from different fetches; that is the rule, not a mixing defect. Today is
 * not settled (whether its high has happened is only known afterwards); the live freeze in
 * [DailyHistoryMaintenance.planSnapshotDisplayedRainChance] keeps it current until the day is over.
 * See plans/261004-forecast-overlay-frozen-at-extreme-time.md.
 */
object ForecastOverlaySettle {
    /**
     * An extreme counts as reached the first time the blended actual line comes within this of the
     * day's max (min), so a flat afternoon does not keep the forecast open after the heat has
     * effectively peaked.
     */
    const val EXTREME_REACHED_TOLERANCE_F = 0.5f

    /** Epoch ms of the first point within [EXTREME_REACHED_TOLERANCE_F] of [target]; null if none. */
    fun <T> firstReachedAt(
        points: List<T>,
        timeOf: (T) -> Long,
        valueOf: (T) -> Float,
        target: Float,
        isHigh: Boolean,
    ): Long? = points
        .filter {
            val v = valueOf(it)
            if (isHigh) v >= target - EXTREME_REACHED_TOLERANCE_F else v <= target + EXTREME_REACHED_TOLERANCE_F
        }
        .minOfOrNull(timeOf)

    /**
     * A forecast value usable for one side of the overlay. Per field, not per row: NWS drops the low
     * from later batches once it has passed, so requiring both would push the high pick back before
     * dawn. Climate normals, generic gap fill and degenerate high==low rows never qualify.
     */
    private fun usable(row: DailyHistoryMaintenance.ForecastHistoryRow, value: Float?): Boolean =
        value != null &&
            !row.isClimateNormal &&
            row.source != WeatherSource.GENERIC_GAP.id &&
            !(row.highTemp != null && row.lowTemp != null && row.highTemp == row.lowTemp)

    data class Settled(
        val row: DailyHistory,
        val highFetchedAt: Long?,
        val lowFetchedAt: Long?,
    )

    /**
     * The settled overlay for one past [history] row, or null when nothing changes. Only forecasts
     * for the same date, source and site are candidates. A side with no known extreme time, or no
     * usable forecast fetched before it (e.g. the site was first fetched after the high), keeps its
     * existing value — a frozen value is never erased.
     */
    fun settle(
        history: DailyHistory,
        forecasts: List<DailyHistoryMaintenance.ForecastHistoryRow>,
        todayMs: Long,
    ): Settled? {
        if (history.date >= todayMs) return null
        val candidates = forecasts.filter {
            it.dateMs == history.date &&
                it.source == history.source &&
                LocationMatch.sameSite(it.locationLat, it.locationLon, history.locationLat, history.locationLon)
        }
        if (candidates.isEmpty()) return null

        fun pick(at: Long?, valueOf: (DailyHistoryMaintenance.ForecastHistoryRow) -> Float?) =
            at?.let { t ->
                candidates.filter { it.fetchedAt <= t && usable(it, valueOf(it)) }.maxByOrNull { it.fetchedAt }
            }

        val highRow = pick(history.computedHighAt) { it.highTemp }
        val lowRow = pick(history.computedLowAt) { it.lowTemp }
        val updated = history.copy(
            forecastHighTemp = highRow?.highTemp ?: history.forecastHighTemp,
            forecastLowTemp = lowRow?.lowTemp ?: history.forecastLowTemp,
            lastWriter = DailyHistoryWriter.FORECAST_FREEZE.storedValue,
        )
        if (updated.forecastHighTemp == history.forecastHighTemp && updated.forecastLowTemp == history.forecastLowTemp) {
            return null
        }
        return Settled(updated, highRow?.fetchedAt, lowRow?.fetchedAt)
    }
}
