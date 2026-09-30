package com.weatherwidget.data.repository

import android.os.SystemClock
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HistorySnapshotRetention
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.log
import java.time.Instant
import java.time.ZoneId

/**
 * Deletes the `hourly_forecast_history` snapshots no reader uses ([HistorySnapshotRetention] is the
 * rule). Runs the Kotlin spec itself, one local day of hours at a time, rather than a second SQL
 * implementation: the rain-accuracy rule is about local calendar days, which SQLite's 'localtime' and
 * a fixed offset both get wrong (process TZ, DST). A day holds at most a few thousand rows.
 *
 * See performance/260929-hourly-history-snapshot-retention.md.
 */
internal class HistorySnapshotPruner(
    private val dao: HourlyForecastHistoryDao,
    private val appLogDao: AppLogDao,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /**
     * Free-list bytes after the prune, logged only: DELETE doesn't shrink the file, but freed pages
     * are reused by later inserts. Whether a VACUUM (exclusive, against Room's WAL pool) is worth it
     * on Android is decided from this number.
     */
    private val freelistBytes: suspend () -> Long? = { null },
) {
    data class Result(val days: Int, val scanned: Int, val deleted: Int, val elapsedMs: Long)

    /**
     * @param touchedSinceFetchedAt only visit days holding hours that received a snapshot fetched at
     *   or after this time. A day's keep set can only change when new snapshots arrive for its hours,
     *   so after the first full pass (0) a daily run skips the settled past. The first pass on the
     *   Pixel scanned 78 days / 250k rows.
     */
    suspend fun prune(touchedSinceFetchedAt: Long = 0L): Result {
        val startMs = SystemClock.elapsedRealtime()
        val zone = zone()
        val min = dao.minDateTime(touchedSinceFetchedAt) ?: return logged(Result(0, 0, 0, 0))
        val max = dao.maxDateTime(touchedSinceFetchedAt) ?: return logged(Result(0, 0, 0, 0))
        var day = Instant.ofEpochMilli(min).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(max).atZone(zone).toLocalDate()
        var days = 0
        var scanned = 0
        var deleted = 0
        while (!day.isAfter(lastDay)) {
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val rows = dao.getAllInDateTimeRange(from, to)
            if (rows.isNotEmpty()) {
                val keep = HistorySnapshotRetention.keptKeys(rows.map { it.toRetentionRow() }, zone)
                val doomed = rows.filter { it.toRetentionRow().key !in keep }
                // Chunked: one statement per row inside Room's transaction; keeps each transaction short.
                doomed.chunked(DELETE_CHUNK).forEach { dao.deleteRows(it) }
                scanned += rows.size
                deleted += doomed.size
            }
            days++
            day = day.plusDays(1)
        }
        return logged(Result(days, scanned, deleted, SystemClock.elapsedRealtime() - startMs))
    }

    private suspend fun logged(result: Result): Result {
        appLogDao.log(
            "HISTORY_PRUNE",
            "days=${result.days} scanned=${result.scanned} deleted=${result.deleted} " +
                "kept=${result.scanned - result.deleted} ms=${result.elapsedMs} " +
                "freelistBytes=${runCatching { freelistBytes() }.getOrNull()}",
            "INFO",
        )
        return result
    }

    companion object {
        private const val DELETE_CHUNK = 500
        const val MIN_INTERVAL_MS = 24L * 3_600_000L

        /**
         * Once a day at most (retention runs after every fetch), and never before the one-shot repairs
         * that read *every* snapshot have run — they would lose their input.
         */
        fun shouldPrune(nowMs: Long, lastPruneMs: Long, repairsDone: Boolean): Boolean =
            repairsDone && nowMs - lastPruneMs >= MIN_INTERVAL_MS
    }
}

internal fun HourlyForecastHistoryEntity.toRetentionRow() = HistorySnapshotRetention.Row(
    source = source,
    dateTime = dateTime,
    lat = locationLat,
    lon = locationLon,
    bucket = timestampToGroupPredictions,
    fetchedAt = fetchedAt,
    cloudCover = cloudCover,
    cloudCoverLow = cloudCoverLow,
    cloudCoverMid = cloudCoverMid,
    cloudCoverHigh = cloudCoverHigh,
    precipProbability = precipProbability,
    precipAmountMm = precipAmountMm,
)
