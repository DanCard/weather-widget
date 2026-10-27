package com.weatherwidget.data.local

import com.weatherwidget.data.model.SettledForecastPick
import java.time.Instant
import java.time.ZoneId

/**
 * Which `hourly_forecast_history` snapshots anything reads — the rest can be deleted.
 *
 * Every fetch writes a full snapshot of every hour it returns, so an hour at a site the phone stayed
 * at holds ~30 copies per source (Kyiv, 2026-09-29), and the table plus its indexes was 50 MB of a
 * 95 MB database. Per (source, site, hour) — site = the exact stored coordinates — the readers need:
 *
 * 1. **Newest** by `timestampToGroupPredictions` and by `fetchedAt`: the one-time desktop/Android
 *    backfills read the freshest per hour, as does `HourlyForecastSelector`.
 * 2. **Newest non-null per coalesced field** by bucket (NWS near-term skyCover).
 * 5. **Settled pick** ([SettledForecastPick]): `HourlyForecastStitcher` (Android
 *    `HourlyForecastLoader`/`GraphDataLoader`, desktop `getHourlyWithHistory`) and
 *    `DailyNoonCloudCover` read the newest row fetched at or before the hour — else the earliest —
 *    and fill its null fields from that same window, newest first. So keep that row and, per
 *    coalesced field, the newest non-null row in the window. Before this rule (2a3293ef) the
 *    pre-hour row was kept only when it happened to be the previous day's newest, so a same-day
 *    11:44 forecast for noon was pruned behind a 12:01 hindcast and the frozen line reverted.
 * 3. **Prior-day band:** newest bucket at least [BAND_LEAD_MS] before the hour among rows carrying a
 *    mid or high band (`PriorDayBandForecast.select`).
 * 4. **Rain accuracy:** newest bucket captured on the hour's previous local calendar day
 *    (`RainAccuracyCalculator` — "1-day-ahead"). Local = [zone], the device zone at prune time.
 *
 * Each is "the newest row within a window that never moves", and new snapshots only ever arrive with
 * later buckets, so a row that is not the newest in its window now can never become it later —
 * pruning is safe for future hours as well as past ones, and idempotent. Rule 5 for an hour not yet
 * started keeps the newest fetch, which is also the newest pre-hour row once it starts; its
 * earliest-row fallback applies only when no row predates the hour, and none can arrive afterwards.
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

    /** Active window where multi-snapshot rules run (30 days); older hours are settled to 1 row per hour per city cluster. */
    const val ACTIVE_WINDOW_MS = RetentionPolicy.DEFAULT_DAYS * 24L * 3_600_000L

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

        val cutoffActiveMs = nowMs - ACTIVE_WINDOW_MS
        val (activeRows, settledRows) = rows.partition { it.dateTime >= cutoffActiveMs }

        // Tier 1: Active 30-day window
        if (activeRows.isNotEmpty()) {
            val clusterMap = clusterCenters(activeRows, LocationMatch.SAME_SITE_TOLERANCE_DEG)
            activeRows.groupBy {
                val cluster = clusterMap[it.lat to it.lon] ?: (it.lat to it.lon)
                GroupKey(it.source, it.dateTime, cluster.first, cluster.second)
            }.forEach { (_, group) ->
                // 1. newest, both ways (ties: keep every tied row — readers break ties arbitrarily)
                keepMax(group, kept) { it.bucket }
                keepMax(group, kept) { it.fetchedAt }
                // 2. newest non-null per coalesced field
                for (field in coalescedFields) keepMax(group.filter { field(it) != null }, kept) { it.bucket }
                // 3. prior-day band
                val hour = group.first().dateTime
                keepMax(
                    group.filter { it.bucket <= hour - BAND_LEAD_MS && (it.cloudCoverMid != null || it.cloudCoverHigh != null) },
                    kept,
                ) { it.bucket }
                // 4. captured on the previous local calendar day
                val day = Instant.ofEpochMilli(hour).atZone(zone).toLocalDate()
                val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val prevDayStart = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                keepMax(group.filter { it.bucket in prevDayStart until dayStart }, kept) { it.bucket }
                // 5. settled pick and its per-field backfill, from the same window (ties: keep all)
                val window = SettledForecastPick.backfillOrder(group, hour, nowMs) { it.fetchedAt }
                keepFetchedAtOf(window.firstOrNull(), window, kept)
                for (field in coalescedFields) {
                    val withField = window.filter { field(it) != null }
                    keepFetchedAtOf(withField.firstOrNull(), withField, kept)
                }
            }
        }

        // Tier 2: Settled archive (30 days to 18 months)
        if (settledRows.isNotEmpty()) {
            val clusterMap = clusterCenters(settledRows, LocationMatch.TOLERANCE_DEG)
            settledRows.groupBy {
                val cluster = clusterMap[it.lat to it.lon] ?: (it.lat to it.lon)
                GroupKey(it.source, it.dateTime, cluster.first, cluster.second)
            }.forEach { (_, group) ->
                // Keep strictly one row per hour per source per city cluster: the stitcher's settled
                // pick, so an archived hour draws the same forecast it did while active. Ties on
                // fetchedAt break to the latest bucket.
                val hour = group.first().dateTime
                val pickedAt = SettledForecastPick.pick(group, hour, nowMs) { it.fetchedAt }!!.fetchedAt
                kept += group.filter { it.fetchedAt == pickedAt }.maxBy { it.bucket }.key
            }
        }

        return kept
    }

    private fun clusterCenters(rows: List<Row>, toleranceDeg: Double): Map<Pair<Double, Double>, Pair<Double, Double>> {
        val freq = rows.groupingBy { it.lat to it.lon }.eachCount()
        val sortedPoints = freq.keys.sortedByDescending { freq[it] ?: 0 }
        val centers = mutableListOf<Pair<Double, Double>>()
        val mapping = mutableMapOf<Pair<Double, Double>, Pair<Double, Double>>()
        for (pt in sortedPoints) {
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
