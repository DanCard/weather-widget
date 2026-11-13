package com.weatherwidget.data.model

import com.weatherwidget.data.local.LocationMatch

/**
 * Which already-elapsed hours of a fetched hourly payload may be filed into
 * `hourly_forecasts`.
 *
 * The hourly graph's forecast line for past hours is drawn from history rows that an *earlier*
 * fetch wrote while those hours were still in the future ([HourlyForecastStitcher]: the live table
 * is latest-only and, by design, never receives an elapsed hour). A fresh install, a new location
 * or a recreated database has no earlier fetch, so the line is blank for every hour before the
 * first fetch — for a whole day. Yet every provider's payload already carries those hours
 * (Open-Meteo `past_days`, Silurian `include_past`, Tomorrow.io `nowMinus23h`, NWS's raw
 * gridpoint back to the issuance start), and the write path threw them away.
 *
 * One rule: **every elapsed hour the payload carries inside [window] is filed**, stamped with this
 * fetch's time, so the stitcher's newest-wins reads it. Until 2026-11-04 an hour was filed only when
 * the source had no row for it (a hindcast must not beat the pre-hour freeze); with the freeze gone
 * that dropped exactly the data a device asleep overnight needed — desktop kept NWS's 18:45 5% for
 * 1am while its 04:00 refresh carried the grid's 25% (plans/261104-file-every-elapsed-hour.md).
 *
 * [ELAPSED_BOUNDARY_MS] is the same boundary the live-table filter uses, so both writes agree
 * about which side of "now" an hour is on: an hour goes to exactly one of the two paths.
 */
object ElapsedForecastBackfill {

    /** Hours at or after `now - ELAPSED_BOUNDARY_MS` belong to the live table, not here. */
    const val ELAPSED_BOUNDARY_MS = 3_600_000L

    /** The hourly loader reads 72 h back; anything older is never drawn from this table. */
    const val LOOKBACK_MS = 72L * 3_600_000L

    /** Hours [nowMs - LOOKBACK_MS, nowMs - ELAPSED_BOUNDARY_MS) — the window a backfill may fill. */
    fun window(nowMs: Long): LongRange = (nowMs - LOOKBACK_MS) until (nowMs - ELAPSED_BOUNDARY_MS)

    /** The rows to file: elapsed hours of [fetched] inside [window], in time order. Duplicate hours collapse to the last one. */
    fun select(fetched: List<HourlyForecast>, nowMs: Long): List<HourlyForecast> {
        val range = window(nowMs)
        return fetched
            .filter { it.dateTime in range }
            .associateBy { it.dateTime }
            .values
            .sortedBy { it.dateTime }
    }

    /** One backfill's outcome: hours the payload offered in the window, rows written. */
    data class Summary(val offered: Int, val stored: Int)

    /** One source's `hourly_forecasts` at one site. */
    interface Store {
        /** Files [rows] under the platform's snapshot bucket for this fetch. */
        suspend fun insert(rows: List<HourlyForecast>)
    }

    /** Files [fetched]'s elapsed hours into [store] ([select]); null when it offered none in the window. */
    suspend fun backfill(
        fetched: List<HourlyForecast>,
        nowMs: Long,
        store: Store,
        stored: List<HourlyForecast> = emptyList(),
    ): Summary? {
        val range = window(nowMs)
        val offered = fetched.count { it.dateTime in range }
        if (offered == 0) return null
        val storedAmounts = newestAmountByHour(stored)
        // Stamped here, not per platform: newest-wins must read this fetch as the newest on both.
        val selected = select(fetched, nowMs).map {
            it.copy(fetchedAt = nowMs, precipAmountMm = it.precipAmountMm ?: storedAmounts[it.dateTime])
        }
        store.insert(selected)
        return Summary(offered, selected.size)
    }

    /**
     * Each hour's newest stored rain amount. A provider whose amount comes from a forward-looking grid
     * (NWS QPF) returns none for an hour that has elapsed, and filing that null would erase the amount
     * an earlier fetch recorded while the hour was still ahead (NWS Fri 2026-11-13: the 09:57 fetch's
     * null 08:00 hour blanked the whole day's amount; plans/261113-nws-day-amount-null-elapsed-hour.md).
     */
    private fun newestAmountByHour(stored: List<HourlyForecast>): Map<Long, Float> =
        stored.filter { it.precipAmountMm != null }
            .groupBy { it.dateTime }
            .mapValues { (_, rows) -> rows.maxBy { it.fetchedAt }.precipAmountMm!! }

    const val LOG_TAG = "HOURLY_HISTORY_BACKFILL"

    fun logMessage(sourceId: String, latitude: Double, longitude: Double, summary: Summary): String =
        "source=$sourceId site=${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)} " +
            "offered=${summary.offered} stored=${summary.stored}"

    /** INFO when something was written (one line per fetch with a look-back), else VERBOSE. */
    fun logLevel(summary: Summary): String = if (summary.stored > 0) "INFO" else "VERBOSE"
}
