package com.weatherwidget.data.local

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
}
