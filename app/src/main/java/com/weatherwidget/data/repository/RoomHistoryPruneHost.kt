package com.weatherwidget.data.repository

import android.content.SharedPreferences
import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log

/**
 * Room side of [HistoryPruneRun]. [touchedSinceFetchedAt] and [startedAtMs] are this pass's
 * [HistorySnapshotPruner] scan.
 */
internal class RoomHistoryPruneHost(
    private val db: WeatherDatabase,
    private val prefs: SharedPreferences,
    private val touchedSinceFetchedAt: Long,
    private val startedAtMs: Long,
) : HistoryPruneRun.Host {
    override suspend fun deleteOlderThan(cutoffMs: Long) = db.hourlyForecastHistoryDao().deleteOldHistory(cutoffMs)

    override suspend fun pruneSnapshots() {
        HistorySnapshotPruner(
            dao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            freelistBytes = { freelistBytes() },
        ).prune(touchedSinceFetchedAt = touchedSinceFetchedAt, nowMs = startedAtMs)
    }

    override suspend fun freelistBytes(): Long {
        val sql = db.openHelper.readableDatabase
        val pages = sql.query("PRAGMA freelist_count").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val size = sql.query("PRAGMA page_size").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return pages * size
    }

    override suspend fun vacuum() = db.openHelper.writableDatabase.execSQL("VACUUM")

    override suspend fun log(message: String) = db.appLogDao().log(HistoryPruneSchedule.LOG_TAG, message, "INFO")

    override suspend fun recordCompleted(startedAtMs: Long) {
        prefs.edit()
            .putLong(HistoryPruneWorker.KEY_LAST_COMPLETED_START_MS, startedAtMs)
            .putBoolean(HistoryPruneWorker.KEY_SETTLED_MIGRATION_V1, true)
            .apply()
    }
}
