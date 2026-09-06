package com.weatherwidget.shared.observations

/**
 * Floors how densely a *personal* weather station may be stored.
 *
 * Synoptic's personal stations report every ~5 minutes and, measured 2026-09-06 on the reporting
 * device, held **43,764 of 54,433 Synoptic rows (80%)**. What that volume buys is small twice over:
 * they are weighted **0.05** in the IDW blend (`personal_station_discount = 95`), and not one of them
 * has ever reported a sky condition — every cloud-bearing Synoptic row belongs to KNUQ, KPAO or
 * KSJC. Meanwhile the remaining widget cost is **disk, not CPU**: at identical row counts a cold read
 * cost `sql=2438 ms` against a warm `sql=353 ms`, so the table's size is what the first tap after a
 * process eviction pays for.
 *
 * Only six stations actually report faster than [BUCKET_MS] (F4751, G6550, G4110, E7138, F9422,
 * E8945 — all personal); the rest already sit at 10–15 minutes and pass through untouched. Halving
 * those six removes roughly 19% of the whole `observations` table.
 *
 * **Personal is decided by station type, never by observed interval.** KSJC reports every 4.6 minutes
 * and is OFFICIAL, full-weight, sky-reporting and the anchor of the NWS blend; an interval-based rule
 * would thin exactly the station that must not be thinned. `HourlyObservationBackfill` already keys
 * on the same field.
 */
object PersonalStationThinning {

    /** Minimum spacing kept for a personal station. */
    const val BUCKET_MS = 10 * 60 * 1000L

    /** The `stationType` value that marks a non-official station. */
    const val PERSONAL = "PERSONAL"

    /**
     * Drops personal-station rows that fall in a [BUCKET_MS] bucket another kept row already covers.
     *
     * **Bucketed on absolute time, not walked forward from the batch's first row.** Fetch windows
     * overlap (a deep pull is 24 h, a shallow one 2 h), so a rule that chained off "the last row I
     * kept" would keep a *different* subset each time the window shifted and, since rows accumulate,
     * would store more rows rather than fewer. `timestamp / BUCKET_MS` is stable no matter where the
     * window starts, so repeated fetches of the same data converge on the same kept set.
     *
     * Within a bucket the earliest row wins, which makes the choice depend only on the data. A batch
     * that starts mid-bucket can keep a later row than a full pull would, adding at most one extra
     * row per boundary — bounded, and erased by retention.
     *
     * Two things always survive:
     *  - every OFFICIAL row, untouched;
     *  - each station's **newest** row, whatever its spacing, because latest-reading staleness drives
     *    `DOMINANT_STATION`, the `readingAgeMin` badge and the backfill's `latest_gap_min` gate.
     *
     * Input order is irrelevant and output order is the input's, so callers keep whatever ordering
     * they had.
     */
    fun <T> thin(
        rows: List<T>,
        stationOf: (T) -> String,
        stationTypeOf: (T) -> String?,
        timestampOf: (T) -> Long,
    ): List<T> {
        if (rows.isEmpty()) return rows

        val newestByStation = HashMap<String, Long>()
        for (row in rows) {
            if (!isPersonal(stationTypeOf(row))) continue
            val station = stationOf(row)
            val timestamp = timestampOf(row)
            val current = newestByStation[station]
            if (current == null || timestamp > current) newestByStation[station] = timestamp
        }

        // Earliest timestamp per (station, bucket) — the row that survives its bucket.
        val keeperByBucket = HashMap<String, Long>()
        for (row in rows) {
            if (!isPersonal(stationTypeOf(row))) continue
            val timestamp = timestampOf(row)
            val key = bucketKey(stationOf(row), timestamp)
            val current = keeperByBucket[key]
            if (current == null || timestamp < current) keeperByBucket[key] = timestamp
        }

        return rows.filter { row ->
            if (!isPersonal(stationTypeOf(row))) return@filter true
            val timestamp = timestampOf(row)
            val station = stationOf(row)
            if (newestByStation[station] == timestamp) return@filter true
            keeperByBucket[bucketKey(station, timestamp)] == timestamp
        }
    }

    /** Null and unknown types are treated as official — never thin what you cannot identify. */
    private fun isPersonal(stationType: String?): Boolean = stationType == PERSONAL

    private fun bucketKey(station: String, timestampMs: Long): String =
        "$station|${Math.floorDiv(timestampMs, BUCKET_MS)}"
}
