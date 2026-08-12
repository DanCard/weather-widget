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

    /**
     * The SQL form of [sameSite] — the tight box, deliberately **not** [ROOM_WHERE]'s ±0.1°.
     *
     * Reads want the coarse box ("near the user"); a row-*deleting* query must not, or it takes out
     * everything within ~7 miles of the target. `BETWEEN` is inclusive, matching [sameSite]'s `<=`.
     */
    const val ROOM_SAME_SITE_WHERE =
        "locationLat BETWEEN :lat - $SAME_SITE_TOLERANCE_DEG AND :lat + $SAME_SITE_TOLERANCE_DEG " +
            "AND locationLon BETWEEN :lon - $SAME_SITE_TOLERANCE_DEG AND :lon + $SAME_SITE_TOLERANCE_DEG"

    /**
     * Collapses a raw [ROOM_WHERE]/[JDBC_WHERE] proximity-box result to the single physical site
     * nearest (lat, lon). Sub-precision fragments of that site survive (they are [sameSite]);
     * genuinely different markers left behind by earlier GPS fixes are dropped.
     *
     * The box query is a coarse pre-filter, never the final answer: at ±[TOLERANCE_DEG] it spans
     * ~7 miles, so a site the device visited earlier that day can sit inside it (37.3414/-122.0422
     * is 0.075° from 37.4168/-122.089 — a different town, still in the box). Those sites stop being
     * refreshed, and their frozen rows then leak into whatever reads the box: a stale LOS GATOS
     * station appeared as a 6th entry in the stations list when only 5 are ever fetched, and a
     * 2-day-old noon cloud row won a `firstOrNull` in DailyNoonCloudCover and flapped the daily bar.
     * Any new read of a coordinate-keyed table should pass its box result through here.
     *
     * [latOf]/[lonOf] read the row's stored *device* location (`locationLat`/`locationLon` — where
     * the fetch happened), not the coordinates of the station the row describes. Passing the latter
     * would rank rows by station distance and defeat the filter. Rows are returned unchanged when
     * empty, so a caller with no data never sees an empty list turn into a silent site choice.
     */
    fun <T> selectNearestSite(
        rows: List<T>,
        lat: Double,
        lon: Double,
        latOf: (T) -> Double,
        lonOf: (T) -> Double,
    ): List<T> {
        val nearest = rows.asSequence()
            .map { latOf(it) to lonOf(it) }
            .distinct()
            .minByOrNull { (rowLat, rowLon) -> abs(rowLat - lat) + abs(rowLon - lon) }
            ?: return rows
        return rows.filter { sameSite(latOf(it), lonOf(it), nearest.first, nearest.second) }
    }

    /**
     * Decimal places lat/lon are rounded to before being written into a location-keyed table's
     * primary key. 3 dp ≈ 111 m — coarse enough that geocoding/GPS jitter (observed on-device up to
     * ~0.0001°, i.e. the 4th decimal) collapses onto a single key so `INSERT … ON CONFLICT REPLACE`
     * actually overwrites instead of accumulating a new per-precision fragment, yet finer than the
     * spacing of genuinely-different markers (≥0.005°, e.g. the default location vs a GPS fix) so
     * those stay distinct. Stays inside [SAME_SITE_TOLERANCE_DEG] (0.002°), so even a residual
     * boundary-straddle is still merged by [sameSite] on the read path.
     */
    const val WRITE_QUANTIZE_DECIMALS = 3

    private val QUANTIZE_FACTOR = Math.pow(10.0, WRITE_QUANTIZE_DECIMALS.toDouble())

    /** Rounds a coordinate component to [WRITE_QUANTIZE_DECIMALS] for use as a stable storage key. */
    fun quantize(coordinate: Double): Double = Math.round(coordinate * QUANTIZE_FACTOR) / QUANTIZE_FACTOR
}
