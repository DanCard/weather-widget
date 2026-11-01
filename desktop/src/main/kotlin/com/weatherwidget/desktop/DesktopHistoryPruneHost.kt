package com.weatherwidget.desktop

import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.desktop.DesktopWeatherDao

/** Desktop SQLite side of [HistoryPruneRun]. */
class DesktopHistoryPruneHost(private val dao: DesktopWeatherDao) : HistoryPruneRun.Host {
    override suspend fun deleteOlderThan(cutoffMs: Long) = dao.deleteOldHourlyForecasts(cutoffMs)

    override suspend fun pruneSnapshots() {
        dao.pruneHourlyHistorySnapshots()
    }

    override suspend fun freelistBytes(): Long = dao.freelistBytes()

    override suspend fun vacuum() = dao.vacuum()

    override suspend fun log(message: String) = dao.log(HistoryPruneSchedule.LOG_TAG, message)

    override suspend fun recordCompleted(startedAtMs: Long) =
        dao.replaceLogMarker(HistoryPruneSchedule.LAST_RUN_TAG, "completed", startedAtMs)
}
