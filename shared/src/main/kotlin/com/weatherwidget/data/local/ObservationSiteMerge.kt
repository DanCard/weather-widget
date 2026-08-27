package com.weatherwidget.data.local

import kotlin.math.abs

/**
 * Collapses the *device-site* axis of an observation read without discarding observations.
 *
 * A device site is not where the weather is. `locationLat`/`locationLon` on an observation row
 * records where the device was standing when it fetched — fetch provenance. The weather is at the
 * stations: KSJC 16 km out, KNUQ 4 km out. Two device coordinates a few hundred metres apart blend
 * *the same stations reporting the same sky*.
 *
 * Measured 2026-08-27 on a Samsung Fold: an ~800 m walk (783 m, computed from the stored
 * coordinates) put every observation from 12:00–13:15 under a second fragment. Back home,
 * [LocationMatch.selectNearestSite] picked the home fragment and deleted all 65 of the other's
 * rows, and both the cloud and the temperature actual lines drew a 75-minute hole over data sitting
 * in the database. NWS had reported normally throughout.
 *
 * That single-site collapse is right for **forecasts** — a forecast is model output for a point —
 * and wrong for **observations**. This is the observation-side rule.
 *
 * Related, and deliberately not reused here:
 *  - [LocationMatch.selectNearestSite] still governs forecast reads.
 *  - [LocationMatch.selectNearestSiteWith] skips sites where *every* row is unusable. It was written
 *    for this same 0.8 km excursion and cannot close this hole: the home fragment holds plenty of
 *    usable rows, so it wins on distance and the other fragment is discarded anyway.
 *  - `HourlyForecastSelector`'s freshest-wins resolves duplicates *within* one site, where only
 *    `fetchedAt` can separate them (see commit 674ab7b0).
 */
object ObservationSiteMerge {

    /**
     * How far a device-site fragment may sit from the query centre and still describe the same sky.
     *
     * Sized by [distanceKm]'s error budget, not by taste. `distanceKm` is stored relative to the
     * fetch location and drives the IDW weights in both blends; it cannot be recomputed from stored
     * fields, because no station coordinate is kept and a distance alone has no bearing. So a merged
     * fragment's rows carry weights off by at most this tolerance. At the measured 783 m that is
     * KNUQ at 4.1 km from the court against 3.8 km from home — a 7.5% distance error, roughly 16% of
     * one station's weight, against the alternative of no data at all. Across the full
     * [LocationMatch.TOLERANCE_DEG] read box (0.1° ≈ 11 km) the same error would make the weights
     * meaningless, which is exactly why this sits well below it.
     *
     * ≈1.1 km of latitude, ≈0.9 km of longitude at 37°N. Deliberately between
     * [LocationMatch.SAME_SITE_TOLERANCE_DEG] (0.002) and [LocationMatch.TOLERANCE_DEG] (0.1).
     */
    const val MERGE_TOLERANCE_DEG = 0.01

    /**
     * [LocationMatch.selectNearestSite]'s Manhattan ranking, measured on the write grid.
     *
     * Grid units for the same reason [withinTolerance] uses them: two fragments that are genuinely
     * equidistant — 37.422 and 37.412 either side of 37.417 — differ by one part in 10^13 as raw
     * doubles, which would silently decide the winner before the `fetchedAt` tie-break could ever be
     * consulted. Rounding first makes "equidistant" a state the tie-break can actually see.
     */
    private fun siteDistance(lat: Double, lon: Double, rowLat: Double, rowLon: Double): Long =
        gridUnits(rowLat, lat) + gridUnits(rowLon, lon)

    /**
     * Compared in whole thousandths of a degree rather than as a raw `<=` on the difference.
     *
     * Write coordinates are quantized to [LocationMatch.WRITE_QUANTIZE_DECIMALS] (3 dp), so a
     * fragment sitting exactly [MERGE_TOLERANCE_DEG] away is an ordinary, reachable case — and in
     * doubles `37.427 - 37.417` is 0.00999999999999801 while `37.417 - 37.407` is
     * 0.01000000000000512, so a raw comparison admits one and rejects the other for no reason but
     * float noise. Rounding both sides to the write grid first makes the boundary exact and
     * symmetric.
     */
    private fun withinTolerance(lat: Double, lon: Double, rowLat: Double, rowLon: Double): Boolean {
        val toleranceUnits = Math.round(MERGE_TOLERANCE_DEG * GRID)
        return gridUnits(rowLat, lat) <= toleranceUnits && gridUnits(rowLon, lon) <= toleranceUnits
    }

    private const val GRID = 1_000.0

    private fun gridUnits(a: Double, b: Double): Long =
        abs(Math.round(a * GRID) - Math.round(b * GRID))

    /**
     * Keeps every row whose device site is within [MERGE_TOLERANCE_DEG] of ([lat], [lon]), then
     * removes the duplicates that merging creates.
     *
     * **The dedup key is `(station, timestamp, api)`, and the `api` is load-bearing.**
     * `MetarCloudBlender` already collapses the *transport* duplicate — NWS and Synoptic storing the
     * same physical METAR — and its comparator prefers the row whose `api` is the requested
     * provider. Deduping on `(station, timestamp)` here could keep the Synoptic copy and that
     * preference would never get to fire. This function owns the **site** axis only; the transport
     * axis stays downstream where it already works.
     *
     * Among copies of one key the nearest site wins, so the surviving `distanceKm` comes from the
     * most accurate frame available; `fetchedAt` breaks a tie (the codebase's freshest-wins rule),
     * then the coordinates make the order total and query-order independent.
     *
     * When no site is within tolerance — the device has genuinely moved somewhere new — this falls
     * back to [LocationMatch.selectNearestSite], preserving today's behaviour rather than returning
     * nothing.
     */
    fun <T> merge(
        rows: List<T>,
        lat: Double,
        lon: Double,
        latOf: (T) -> Double,
        lonOf: (T) -> Double,
        stationOf: (T) -> String,
        timestampOf: (T) -> Long,
        apiOf: (T) -> String,
        fetchedAtOf: (T) -> Long,
    ): List<T> {
        if (rows.isEmpty()) return rows
        val nearby = rows.filter { withinTolerance(lat, lon, latOf(it), lonOf(it)) }
        if (nearby.isEmpty()) return LocationMatch.selectNearestSite(rows, lat, lon, latOf, lonOf)

        return nearby
            .groupBy { Triple(stationOf(it), timestampOf(it), apiOf(it)) }
            .values
            .map { duplicates ->
                duplicates.minWith(
                    compareBy(
                        { siteDistance(lat, lon, latOf(it), lonOf(it)) },
                        { -fetchedAtOf(it) },
                        { latOf(it) },
                        { lonOf(it) },
                    ),
                )
            }
            // A total order the callers can rely on, matching the blends' own expectations.
            .sortedWith(compareBy({ timestampOf(it) }, { stationOf(it) }, { apiOf(it) }))
    }
}
