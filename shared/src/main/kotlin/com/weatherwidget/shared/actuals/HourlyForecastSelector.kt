package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.Log

/**
 * Single source of truth for collapsing the candidate hourly-forecast rows the persistence layer
 * returns into exactly one forecast per hour. Shared by Android (via
 * `widget.handlers.ForecastSourcePriority`) and desktop / the actual-temperature series builder so
 * the two hand-maintained pipelines can never disagree on which row wins.
 *
 * Why this exists: `hourly_forecasts` is keyed on `(dateTime, source, locationLat, locationLon)`
 * with float lat/lon, so GPS/geocoding jitter (5th–7th decimal) splits one physical site into
 * several per-precision rows that `ON CONFLICT REPLACE` never overwrites. The
 * [LocationMatch.ROOM_WHERE]/[LocationMatch.JDBC_WHERE] proximity box (~7 mi) used at read time then
 * gathers ALL of them — including stale fetches and genuinely-different neighbouring markers. Naively
 * picking by `fetchedAt` across that mixed set surfaced a different value on every device (the
 * reported cross-device disagreement). This selector resolves it deterministically.
 */
object HourlyForecastSelector {
    private const val TAG = "HourlyForecastSelector"
    private const val GENERIC_GAP_SOURCE = "Generic"

    /**
     * Reduce [rows] to one [HourlyForecast] per `dateTime` for [displaySourceId], rendered around
     * the user's site at ([centerLat], [centerLon]).
     *
     * Per hour: 1) keep the display source (else the GENERIC_GAP filler, else everything);
     * 2) drop rows that are not the same physical site as the query centre
     *    ([LocationMatch.sameSite]) — this discards both jitter-stale fragments left at an old fetch
     *    coordinate and genuinely-different neighbouring markers (e.g. the default location vs a GPS
     *    fix), which are farther apart than the same-site tolerance; 3) take the freshest remaining
     *    row. Freshest-wins makes every device converge, because they all share the latest fetch.
     *
     * Past-hour "original prediction" semantics are NOT handled here — those come from the
     * `hourly_forecast_history` snapshots via the stitching layer; this selector governs the live
     * (current/future) forecast line.
     */
    fun selectForecastsByTime(
        rows: List<HourlyForecast>,
        displaySourceId: String,
        centerLat: Double,
        centerLon: Double,
    ): Map<Long, HourlyForecast> =
        rows.groupBy { it.dateTime }
            .mapNotNull { (dateTime, hourRows) ->
                val sourceFiltered = when {
                    hourRows.any { it.source == displaySourceId } -> hourRows.filter { it.source == displaySourceId }
                    hourRows.any { it.source == GENERIC_GAP_SOURCE } -> hourRows.filter { it.source == GENERIC_GAP_SOURCE }
                    else -> hourRows
                }
                // Restrict to the user's actual site. A row with null coords (a consumer that didn't
                // populate them) is kept rather than silently dropped.
                val sameSite = sourceFiltered.filter { row ->
                    val lat = row.locationLat
                    val lon = row.locationLon
                    lat == null || lon == null || LocationMatch.sameSite(centerLat, centerLon, lat, lon)
                }
                val candidates = sameSite.ifEmpty { sourceFiltered }
                val picked = candidates.maxByOrNull { it.fetchedAt }
                if (candidates.size > 1) {
                    Log.d(
                        TAG,
                        "dedup: source=$displaySourceId dt=$dateTime candidates=${candidates.size} " +
                            "picked=${picked?.temperature}/${picked?.condition} " +
                            "fetchedAts=${candidates.map { it.fetchedAt }}",
                    )
                }
                picked?.let { dateTime to it }
            }
            .toMap()
}
