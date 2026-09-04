package com.weatherwidget.data.local

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Separates the two reasons an observation pool can be older than the database actually is.
 *
 * A render that draws stale actuals looks identical either way — the graph simply stops early and
 * the dominant-station label names whatever reading was in effect at the last emitted point. The
 * 2026-09-03 report (`knuq 73.4° @ 5:55 pm` drawn at 18:38, 43 minutes old) cost a full DB
 * reconstruction to get this far, and the answer still needed a per-site `fetchedAt` archaeology
 * pass to rule out the obvious suspect. The numbers below are what that pass produced, so the next
 * occurrence can be read straight out of `app_logs`:
 *
 *  - **[Summary.mergeDroppedFresher]** — the coarse box held rows newer than the merged pool's
 *    newest. The device-site axis ate them: a fragment sat outside [ObservationSiteMerge.MERGE_TOLERANCE_DEG]
 *    of the query centre. This is the familiar coordinate-fragmentation family, and
 *    [Summary.droppedFresherSites] names the fragments so the tolerance can be argued about with
 *    coordinates rather than guesses.
 *  - **not that, but the pool is still stale** — the box itself had nothing newer, so the read is
 *    innocent and the *fetch* (or the query window) is the subject. Nothing about the location
 *    plumbing is worth looking at.
 *
 * On 2026-09-03 the merge was innocent by this test: every KNUQ fragment sat within 0.007° of the
 * centre and the box's newest row was the merged pool's newest. That is precisely the fact that took
 * longest to establish and is cheapest to log.
 */
object ObservationPoolDiagnostics {

    /**
     * How far behind [Instant.now] a merged pool may fall before it is worth a log line.
     *
     * Stations in this app's blend report on 5-, 15- and 20-minute cadences, and a fetch interval of
     * 60 minutes is normal off-charger, so a pool a few minutes old is the healthy steady state. 15
     * minutes is past every station cadence but well inside one fetch interval, which makes it a
     * threshold that fires on the anomaly and stays quiet the rest of the time — this runs on every
     * temperature render, several times a minute across a multi-widget home screen.
     */
    const val STALE_POOL_MINUTES = 15L

    /** Sites named in [Summary.droppedFresherSites]; enough to identify a fragment, bounded so one bad read cannot flood a row. */
    private const val MAX_NAMED_SITES = 4

    data class Summary(
        /** Rows the coarse [LocationMatch.ROOM_WHERE] box returned, before the device-site merge. */
        val candidateCount: Int,
        /** Rows that survived [ObservationSiteMerge.merge] — what the render actually blends. */
        val mergedCount: Int,
        val candidateNewestMs: Long?,
        val mergedNewestMs: Long?,
        /** Distinct device sites among the candidates, on the 3 dp write grid. */
        val siteCount: Int,
        /** Sites holding candidate rows newer than [mergedNewestMs], as `lat,lon@newest(count)`. */
        val droppedFresherSites: List<String>,
    ) {
        /**
         * True when the box held something fresher than the merge kept — the location axis is the
         * subject. False means the read returned everything the box had and the fetch is.
         */
        val mergeDroppedFresher: Boolean
            get() =
                candidateNewestMs != null &&
                    (mergedNewestMs == null || candidateNewestMs > mergedNewestMs)
    }

    fun <T> summarize(
        candidates: List<T>,
        merged: List<T>,
        latOf: (T) -> Double,
        lonOf: (T) -> Double,
        timestampOf: (T) -> Long,
    ): Summary {
        val candidateNewest = candidates.maxOfOrNull(timestampOf)
        val mergedNewest = merged.maxOfOrNull(timestampOf)
        val fresher =
            if (candidateNewest != null && (mergedNewest == null || candidateNewest > mergedNewest)) {
                candidates
                    .filter { mergedNewest == null || timestampOf(it) > mergedNewest }
                    .groupBy { siteKey(latOf(it), lonOf(it)) }
                    .entries
                    .sortedByDescending { entry -> entry.value.maxOf(timestampOf) }
                    .take(MAX_NAMED_SITES)
                    .map { (site, rows) -> "$site@${clock(rows.maxOf(timestampOf))}(${rows.size})" }
            } else {
                emptyList()
            }
        return Summary(
            candidateCount = candidates.size,
            mergedCount = merged.size,
            candidateNewestMs = candidateNewest,
            mergedNewestMs = mergedNewest,
            siteCount = candidates.map { siteKey(latOf(it), lonOf(it)) }.distinct().size,
            droppedFresherSites = fresher,
        )
    }

    /** True when [summary] describes a pool worth a log line — see [STALE_POOL_MINUTES]. */
    fun shouldLog(summary: Summary, nowMs: Long): Boolean {
        if (summary.candidateCount == 0) return false
        if (summary.mergeDroppedFresher) return true
        val newest = summary.mergedNewestMs ?: return true
        return nowMs - newest >= STALE_POOL_MINUTES * 60_000L
    }

    /**
     * One `app_logs` line. Clock times rather than epochs because every question asked of this line
     * ("was 18:15 in the database yet?") is asked in wall-clock terms against the other tags.
     */
    fun format(
        summary: Summary,
        nowMs: Long,
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        fun age(ms: Long?): String =
            ms?.let { ((nowMs - it).coerceAtLeast(0L) / 60_000L).toString() } ?: "na"
        val verdict = if (summary.mergeDroppedFresher) "merge_dropped_fresher" else "box_had_nothing_newer"
        return "centre=${coords(lat, lon)} win=${clock(startTs, zoneId)}..${clock(endTs, zoneId)} " +
            "cand=${summary.candidateCount} candNewest=${clock(summary.candidateNewestMs, zoneId)} " +
            "candNewestAgeMin=${age(summary.candidateNewestMs)} " +
            "merged=${summary.mergedCount} mergedNewest=${clock(summary.mergedNewestMs, zoneId)} " +
            "mergedNewestAgeMin=${age(summary.mergedNewestMs)} " +
            "sites=${summary.siteCount} verdict=$verdict " +
            "fresherSites=${summary.droppedFresherSites.takeIf { it.isNotEmpty() }?.joinToString("|") ?: "none"}"
    }

    /** The 3 dp write grid, so a logged site can be pasted straight into a `locationLat=` query. */
    private fun siteKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.3f,%.3f", lat, lon)

    private fun coords(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.5f,%.5f", lat, lon)

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

    private fun clock(ms: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String =
        ms?.let { runCatching { Instant.ofEpochMilli(it).atZone(zoneId).format(CLOCK) }.getOrNull() ?: "bad" }
            ?: "na"
}
