package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.util.Log

/**
 * Collapses the candidate daily-forecast rows the persistence layer returns into exactly one row
 * per (targetDate, source). The daily sibling of [HourlyForecastSelector], for the same reason:
 * `forecasts` is keyed on float `(targetDate, forecastDate, locationLat, locationLon, source,
 * fetchedAt)`, so GPS/geocoding jitter splits one physical site into several per-precision key
 * silos that new fetches never overwrite. The [LocationMatch.ROOM_WHERE]/[LocationMatch.JDBC_WHERE]
 * proximity box (~7 mi) used at read time gathers ALL of them, and the DAO's freshest-batch
 * subquery keys on exact lat/lon — so a site abandoned by a coordinate hop keeps its last batch
 * alive forever. Observed on-device as a days-stale "tomorrow" high (74° from a June 29 batch
 * displayed over the July 1 batch's 77° because the stale site's row happened to sort first).
 *
 * Generic over the row type because Android's `ForecastEntity` (Room, :app) and any desktop row
 * shape can't be referenced from :shared; callers pass field extractors once at their wrapper.
 */
object DailyForecastSelector {
    private const val TAG = "DailyForecastSelector"

    /**
     * Reduce [rows] — already filtered to the proximity box and to the freshest batch per exact
     * coordinate — to one row per (targetDate, source), for a display centred at
     * ([centerLat], [centerLon]).
     *
     * Per (targetDate, source): 1) drop rows that are not the same physical site as the query
     * centre ([LocationMatch.sameSite]) — discarding both jitter-stale fragments left at an old
     * fetch coordinate and genuinely-different neighbouring markers (e.g. the default location vs
     * a GPS fix), unless that would leave nothing; 2) take the freshest remaining row by
     * ([batchFetchedAt], then [fetchedAt]). Freshest-wins makes every device converge, because
     * they all share the latest fetch.
     *
     * Output preserves targetDate-ascending order (the DAO contract).
     */
    fun <T> selectFreshestPerDaySource(
        rows: List<T>,
        centerLat: Double,
        centerLon: Double,
        targetDate: (T) -> Long,
        source: (T) -> String,
        locationLat: (T) -> Double,
        locationLon: (T) -> Double,
        batchFetchedAt: (T) -> Long,
        fetchedAt: (T) -> Long = { 0L },
    ): List<T> =
        rows.groupBy { targetDate(it) to source(it) }
            .mapNotNull { (key, group) ->
                if (group.size == 1) return@mapNotNull group.first()
                val sameSite = group.filter {
                    LocationMatch.sameSite(centerLat, centerLon, locationLat(it), locationLon(it))
                }
                val candidates = sameSite.ifEmpty { group }
                val picked = candidates.maxWithOrNull(compareBy(batchFetchedAt, fetchedAt))
                Log.d(
                    TAG,
                    "dedup: date=${key.first} source=${key.second} rows=${group.size} " +
                        "sameSite=${sameSite.size} pickedBatch=${picked?.let(batchFetchedAt)} " +
                        "droppedBatches=${group.filter { it !== picked }.map(batchFetchedAt)}",
                )
                picked
            }
            .sortedBy(targetDate)
}
