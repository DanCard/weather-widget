package com.weatherwidget.data.model

import com.weatherwidget.data.local.LocationMatch

/**
 * Which already-elapsed hours of a fetched hourly payload may be filed into
 * `hourly_forecast_history`.
 *
 * The hourly graph's forecast line for past hours is drawn from history rows that an *earlier*
 * fetch wrote while those hours were still in the future ([HourlyForecastStitcher]: the live table
 * is latest-only and, by design, never receives an elapsed hour). A fresh install, a new location
 * or a recreated database has no earlier fetch, so the line is blank for every hour before the
 * first fetch — for a whole day. Yet every provider's payload already carries those hours
 * (Open-Meteo `past_days`, Silurian `include_past`, Tomorrow.io `nowMinus23h`, NWS's raw
 * gridpoint back to the issuance start), and the write path threw them away.
 *
 * One rule, same-source only: **an elapsed hour is filed iff the source has no history row for
 * it at that site.** That is the only case where nothing is overwritten. Where a genuine snapshot
 * exists, the elapsed value is a hindcast and freshest-wins would promote it over the prediction
 * — that is the case the write-side drop exists for, and it still holds.
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

    /**
     * The rows to file: elapsed hours of [fetched] inside [window] that [coveredHours] does not
     * already hold, in time order. Duplicate hours in the payload collapse to the last one.
     */
    fun select(
        fetched: List<HourlyForecast>,
        nowMs: Long,
        coveredHours: Set<Long>,
    ): List<HourlyForecast> {
        val range = window(nowMs)
        return fetched
            .filter { it.dateTime in range && it.dateTime !in coveredHours }
            .associateBy { it.dateTime }
            .values
            .sortedBy { it.dateTime }
    }

    /** One backfill's outcome: hours the payload offered in the window, those already covered, rows written. */
    data class Summary(val offered: Int, val covered: Int, val stored: Int)

    /** One source's `hourly_forecast_history` at one site. */
    interface Store {
        /** Hours with a history row at the site (same-site match, any bucket) in [startMs, endMs). */
        suspend fun coveredHours(startMs: Long, endMs: Long): Set<Long>

        /** Files [rows] under the platform's snapshot bucket for this fetch. */
        suspend fun insert(rows: List<HourlyForecast>)
    }

    /** Files [fetched]'s uncovered elapsed hours into [store] ([select]); null when it offered none in the window. */
    suspend fun backfill(fetched: List<HourlyForecast>, nowMs: Long, store: Store): Summary? {
        val range = window(nowMs)
        val offered = fetched.filter { it.dateTime in range }
        if (offered.isEmpty()) return null
        val covered = store.coveredHours(offered.minOf { it.dateTime }, offered.maxOf { it.dateTime } + 1)
        val selected = select(offered, nowMs, covered)
        if (selected.isNotEmpty()) store.insert(selected)
        return Summary(offered.size, covered.size, selected.size)
    }

    const val LOG_TAG = "HOURLY_HISTORY_BACKFILL"

    fun logMessage(sourceId: String, latitude: Double, longitude: Double, summary: Summary): String =
        "source=$sourceId site=${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)} " +
            "offered=${summary.offered} covered=${summary.covered} stored=${summary.stored}"

    /** INFO only when something was written: in steady state this runs every fetch and stores nothing. */
    fun logLevel(summary: Summary): String = if (summary.stored > 0) "INFO" else "VERBOSE"
}
