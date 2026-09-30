package com.weatherwidget.data.local

import java.time.Instant
import java.time.ZoneId

/**
 * Which `hourly_forecast_history` snapshots anything reads — the rest can be deleted.
 *
 * Every fetch writes a full snapshot of every hour it returns, so an hour at a site the phone stayed
 * at holds ~30 copies per source (Kyiv, 2026-09-29), and the table plus its indexes was 50 MB of a
 * 95 MB database. Per (source, site, hour) — site = the exact stored coordinates — the readers need:
 *
 * 1. **Newest** by `timestampToGroupPredictions` and by `fetchedAt`: `HourlyForecastStitcher` picks
 *    `maxBy { fetchedAt }` (Android `HourlyForecastLoader`/`GraphDataLoader`, desktop
 *    `getHourlyWithHistory`), and the one-time desktop/Android backfills read the freshest per hour.
 * 2. **Newest non-null per coalesced field** by bucket: the stitcher fills a null field on its pick
 *    from the first non-null row in bucket-DESC order (NWS near-term skyCover).
 * 3. **Prior-day band:** newest bucket at least [BAND_LEAD_MS] before the hour among rows carrying a
 *    mid or high band (`PriorDayBandForecast.select`).
 * 4. **Rain accuracy:** newest bucket captured on the hour's previous local calendar day
 *    (`RainAccuracyCalculator` — "1-day-ahead"). Local = [zone], the device zone at prune time.
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

    fun keptKeys(rows: List<Row>, zone: ZoneId): Set<Key> {
        val kept = HashSet<Key>()
        rows.groupBy { GroupKey(it.source, it.dateTime, it.lat, it.lon) }.forEach { (_, group) ->
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
        }
        return kept
    }

    private data class GroupKey(val source: String, val dateTime: Long, val lat: Double, val lon: Double)

    private inline fun keepMax(rows: List<Row>, into: MutableSet<Key>, selector: (Row) -> Long) {
        if (rows.isEmpty()) return
        val max = rows.maxOf(selector)
        rows.filter { selector(it) == max }.forEach { into += it.key }
    }
}
