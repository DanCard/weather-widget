package com.weatherwidget.data.local

import kotlinx.coroutines.CancellationException

/**
 * One housekeeping pass, in the same order on both platforms: the forecast age delete, the
 * [HistorySnapshotRetention] prune, the [HourlyActualsArchiveRun] archive, the raw observation and
 * `hourly_actuals` age deletes, a VACUUM when enough space is free, then the completed marker. Whether
 * a pass runs at all is [HistoryPruneSchedule]'s.
 *
 * Raw observations are deleted only here, only behind what the archive finished: this pass can wait
 * days for an idle charger, and an age delete on every fetch would lose hours never archived.
 *
 * The marker comes last, so a failed prune stays due. A failed VACUUM does not hold it back: the rows
 * are already gone, and the next pass tries again.
 */
object HistoryPruneRun {
    private const val DAY_MS = 24L * 3_600_000L

    interface Host : HourlyActualsArchiveRun.Host {
        suspend fun deleteOlderThan(cutoffMs: Long)

        suspend fun pruneSnapshots()

        /** By reading time, never by fetch time: a backfill's old reading carries a new `fetchedAt`. */
        suspend fun deleteObservationsOlderThan(cutoffMs: Long)

        suspend fun deleteHourlyActualsOlderThan(cutoffMs: Long)

        suspend fun freelistBytes(): Long

        suspend fun vacuum()

        /** Writes to [HistoryPruneSchedule.LOG_TAG]. */
        suspend fun log(message: String)

        suspend fun recordCompleted(startedAtMs: Long)
    }

    suspend fun run(host: Host, nowMs: Long) {
        host.deleteOlderThan(RetentionPolicy.daysAgo(nowMs, RetentionPolicy.HOURLY_FORECAST_DAYS))
        host.pruneSnapshots()
        val archive = HourlyActualsArchiveRun.run(host, nowMs)
        host.log("hourly_actuals days=${archive.days} rows=${archive.rows} through=${archive.throughMs}")
        host.deleteObservationsOlderThan(archive.throughMs - RetentionPolicy.OBSERVATION_ARCHIVE_MARGIN_DAYS * DAY_MS)
        host.deleteHourlyActualsOlderThan(RetentionPolicy.daysAgo(nowMs, RetentionPolicy.HOURLY_ACTUALS_DAYS))
        vacuumIfWorthwhile(host)
        host.recordCompleted(nowMs)
    }

    private suspend fun vacuumIfWorthwhile(host: Host) {
        val freeBytes = host.freelistBytes()
        if (freeBytes < HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES) return
        val startNs = System.nanoTime()
        try {
            host.vacuum()
            host.log("vacuum=done freedBytes=$freeBytes ms=${(System.nanoTime() - startNs) / 1_000_000L}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("vacuum=skipped reason=${e.javaClass.simpleName} freeBytes=$freeBytes")
        }
    }
}
