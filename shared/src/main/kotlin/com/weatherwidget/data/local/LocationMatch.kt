package com.weatherwidget.data.local

import kotlin.math.abs

/**
 * Single source of truth for "does this stored row belong to the user's current location?".
 *
 * A device's saved location can be persisted at slightly different lat/lon precisions across
 * sessions (manual entry like `37.4167` vs geocoded `37.416883`), and `locationLat`/`locationLon`
 * are part of every weather table's key. Matching with exact float equality fragments the cache into
 * per-precision silos — which once left the desktop cloud-cover graph flat because its data sat
 * under a neighbouring coordinate. Both the Android Room DAOs and the desktop JDBC DAO match within
 * the small proximity box defined here, so the rule can never silently drift between the two
 * hand-maintained persistence layers again.
 *
 * The two predicate constants encode the identical box and differ only in parameter syntax:
 *  - [ROOM_WHERE] uses Room named params (`:lat`, `:lon`) — embed in an `@Query`. The enclosing DAO
 *    function must expose `lat: Double` and `lon: Double` parameters.
 *  - [JDBC_WHERE] uses positional `?` placeholders bound **lat then lon** — embed in a
 *    `PreparedStatement` SQL string and bind those two doubles in that order.
 *
 * ~0.1° is roughly 7 miles of latitude (and ~5.5 miles of longitude at 37°N) — far larger than any
 * geocoding precision jitter, yet well under the scale of a genuinely different location.
 */
object LocationMatch {
    const val TOLERANCE_DEG = 0.1

    const val ROOM_WHERE =
        "locationLat BETWEEN :lat - $TOLERANCE_DEG AND :lat + $TOLERANCE_DEG " +
            "AND locationLon BETWEEN :lon - $TOLERANCE_DEG AND :lon + $TOLERANCE_DEG"

    const val JDBC_WHERE =
        "ABS(locationLat - ?) <= $TOLERANCE_DEG AND ABS(locationLon - ?) <= $TOLERANCE_DEG"

    /**
     * Much finer than [TOLERANCE_DEG]: "is this literally the SAME physical site, modulo lat/lon
     * precision jitter?". The coarse 0.1° fetch box is for "near the user" and would wrongly merge two
     * genuinely different nearby markers; this box is for collapsing the sub-precision fragments of one
     * site that accumulate across fetches (e.g. `37.4168014` vs `37.4168434` — the same spot, ~tens of
     * metres apart). Used by in-memory unification AFTER the rows are fetched, where exact float
     * equality would silently drop a fragment (e.g. the morning forecast rows) and blank part of the
     * graph. Observed fragments are ~0.0001° apart; genuinely different markers (e.g. 37.422 vs 37.4168)
     * are ~0.005° apart, so 0.002° (~200 m) sits safely between — `≫` jitter, `≪` real marker spacing.
     */
    const val SAME_SITE_TOLERANCE_DEG = 0.002

    /** True when (lat1,lon1) and (lat2,lon2) are the same site under [SAME_SITE_TOLERANCE_DEG]. */
    fun sameSite(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean =
        abs(lat1 - lat2) <= SAME_SITE_TOLERANCE_DEG && abs(lon1 - lon2) <= SAME_SITE_TOLERANCE_DEG
}
