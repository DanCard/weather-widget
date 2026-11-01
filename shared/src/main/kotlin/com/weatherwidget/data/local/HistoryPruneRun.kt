package com.weatherwidget.data.local

import kotlinx.coroutines.CancellationException

/**
 * One housekeeping pass over `hourly_forecasts`, in the same order on both platforms: the 18-month
 * age delete, the [HistorySnapshotRetention] prune, a VACUUM when enough space is free, then the
 * completed marker. Whether a pass runs at all is [HistoryPruneSchedule]'s.
 *
 * The marker comes last, so a failed prune stays due. A failed VACUUM does not hold it back: the rows
 * are already gone, and the next pass tries again.
 */
object HistoryPruneRun {
    interface Host {
        suspend fun deleteOlderThan(cutoffMs: Long)

        suspend fun pruneSnapshots()

        suspend fun freelistBytes(): Long

        suspend fun vacuum()

        /** Writes to [HistoryPruneSchedule.LOG_TAG]. */
        suspend fun log(message: String)

        suspend fun recordCompleted(startedAtMs: Long)
    }

    suspend fun run(host: Host, nowMs: Long) {
        host.deleteOlderThan(RetentionPolicy.daysAgo(nowMs, RetentionPolicy.HOURLY_HISTORY_SETTLED_DAYS))
        host.pruneSnapshots()
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
