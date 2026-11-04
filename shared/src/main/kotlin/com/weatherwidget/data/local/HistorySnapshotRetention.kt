package com.weatherwidget.data.local

import com.weatherwidget.data.model.SettledForecastPick
import java.time.Instant
import java.time.ZoneId

/**
 * Which `hourly_forecasts` snapshots anything reads — the rest can be deleted.
 *
 * Every fetch writes a full snapshot of every hour it returns, so an hour at a site the phone stayed
 * at holds ~30 copies per source (Kyiv, 2026-09-29), and the table plus its indexes was 50 MB of a
 * 95 MB database. Per (source, site, hour) — site = the exact stored coordinates — the readers need:
 *
 * 1. **Newest** by `timestampToGroupPredictions` and by `fetchedAt`: the one-time desktop/Android
 *    backfills read the freshest per hour, as does `HourlyForecastSelector`.
 * 2. **Settled pick** ([SettledForecastPick]): `HourlyForecastStitcher` (Android
 *    `HourlyForecastLoader`/`GraphDataLoader`, desktop `getHourlyWithHistory`) and
 *    `DailyNoonCloudCover` read the newest row, started hour or not (the pre-hour freeze was removed
 *    2026-11-04), and fill its null fields from the next-newest rows. So keep that row and, per
 *    coalesced field, the newest non-null row.
 * 3. **Prior-day band:** newest bucket at least [BAND_LEAD_MS] before the hour among rows carrying a
 *    mid or high band (`PriorDayBandForecast.select`).
 * 4. **Rain accuracy:** newest bucket captured on the hour's previous local calendar day
 *    (`RainAccuracyCalculator` — "1-day-ahead"). Local = [zone], the device zone at prune time.
 *
 * Rule 1 runs only for hours under [FULL_WINDOW_MS] old: those hindcasts draw nothing past that age.
 * Hours past [RETAINED_WINDOW_MS] keep no row at all.
 *
 * Each is "the newest row within a window that never moves", and new snapshots only ever arrive with
 * later buckets, so a row that is not the newest in its window now can never become it later —
 * pruning is safe for future hours as well as past ones, and idempotent.
 *
 * Keeping each site's own maxima preserves every reader's cross-site maximum, so readers that merge
 * sites (the stitcher's same-site/borrow sets, rain accuracy's unfiltered box) see the same result.
 * The one-shot repairs that read *all* snapshots (`FrozenRainChanceRepair`, the frozen-display and
 * chance backfills) must have run first; callers gate on that.
 *
 * This object is the spec. The SQL prune (Android and desktop) is tested against it.
 */
object HistorySnapshotRetention {
    /** Matches `PriorDayBandForecast.LEAD_MS`. */
    const val BAND_LEAD_MS = 24L * 3_600_000L

    /** Hours younger than this keep rule 1's newest snapshots as well. */
    const val FULL_WINDOW_MS = RetentionPolicy.HOURLY_FORECAST_FULL_DAYS * 24L * 3_600_000L

    /** Hours older than this keep nothing. */
    const val RETAINED_WINDOW_MS = RetentionPolicy.HOURLY_FORECAST_DAYS * 24L * 3_600_000L

    data class Row(
        val source: String,
        val dateTime: Long,
        val lat: Double,
        val lon: Double,
        val bucket: Long,
        val fetchedAt: Long,
        val cloudCover: Int? = null,
        val cloudCoverLow: Int? = null,
        val cloudCoverMid: Int? = null,
        val cloudCoverHigh: Int? = null,
        val precipProbability: Int? = null,
        val precipAmountMm: Float? = null,
    ) {
        /** The table's primary key minus nothing: (dateTime, source, lat, lon, bucket). */
        val key: Key get() = Key(dateTime, source, lat, lon, bucket)
    }

    data class Key(val dateTime: Long, val source: String, val lat: Double, val lon: Double, val bucket: Long)

    private val coalescedFields: List<(Row) -> Any?> = listOf(
        Row::cloudCover, Row::cloudCoverLow, Row::cloudCoverMid, Row::cloudCoverHigh,
        Row::precipProbability, Row::precipAmountMm,
    )

    fun keptKeys(
        rows: List<Row>,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis(),
    ): Set<Key> {
        val kept = HashSet<Key>()
        if (rows.isEmpty()) return kept

        val fullCutoffMs = nowMs - FULL_WINDOW_MS
        val retained = rows.filter { it.dateTime >= nowMs - RETAINED_WINDOW_MS }
        if (retained.isEmpty()) return kept

        val clusterMap = clusterCenters(
            retained.groupingBy { it.lat to it.lon }.eachCount(),
            LocationMatch.SAME_SITE_TOLERANCE_DEG,
        )
        retained.groupBy {
            val cluster = clusterMap[it.lat to it.lon] ?: (it.lat to it.lon)
            GroupKey(it.source, it.dateTime, cluster.first, cluster.second)
        }.forEach { (_, group) ->
            val hour = group.first().dateTime
            // 1. newest, both ways (ties: keep every tied row — readers break ties arbitrarily)
            if (hour >= fullCutoffMs) {
                keepMax(group, kept) { it.bucket }
                keepMax(group, kept) { it.fetchedAt }
            }
            // 3. prior-day band
            keepMax(
                group.filter { it.bucket <= hour - BAND_LEAD_MS && (it.cloudCoverMid != null || it.cloudCoverHigh != null) },
                kept,
            ) { it.bucket }
            // 4. captured on the previous local calendar day
            val day = Instant.ofEpochMilli(hour).atZone(zone).toLocalDate()
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val prevDayStart = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            keepMax(group.filter { it.bucket in prevDayStart until dayStart }, kept) { it.bucket }
            // 2. settled pick and its per-field backfill, from the same window (ties: keep all)
            val window = SettledForecastPick.backfillOrder(group) { it.fetchedAt }
            keepFetchedAtOf(window.firstOrNull(), window, kept)
            for (field in coalescedFields) {
                val withField = window.filter { field(it) != null }
                keepFetchedAtOf(withField.firstOrNull(), withField, kept)
            }
        }

        return kept
    }

    /** Each point's cluster centre, most frequent points first. [counts]: rows per exact point. */
    fun clusterCenters(counts: Map<Pair<Double, Double>, Int>, toleranceDeg: Double): Map<Pair<Double, Double>, Pair<Double, Double>> {
        val centers = mutableListOf<Pair<Double, Double>>()
        val mapping = mutableMapOf<Pair<Double, Double>, Pair<Double, Double>>()
        for (pt in counts.keys.sortedByDescending { counts[it] ?: 0 }) {
            val matched = centers.firstOrNull { (cLat, cLon) ->
                kotlin.math.abs(pt.first - cLat) <= toleranceDeg &&
                    kotlin.math.abs(pt.second - cLon) <= toleranceDeg
            }
            if (matched != null) {
                mapping[pt] = matched
            } else {
                centers += pt
                mapping[pt] = pt
            }
        }
        return mapping
    }

    private data class GroupKey(val source: String, val dateTime: Long, val lat: Double, val lon: Double)

    private fun keepFetchedAtOf(best: Row?, rows: List<Row>, into: MutableSet<Key>) {
        if (best == null) return
        rows.filter { it.fetchedAt == best.fetchedAt }.forEach { into += it.key }
    }

    private inline fun keepMax(rows: List<Row>, into: MutableSet<Key>, selector: (Row) -> Long) {
        if (rows.isEmpty()) return
        val max = rows.maxOf(selector)
        rows.filter { selector(it) == max }.forEach { into += it.key }
    }
}
