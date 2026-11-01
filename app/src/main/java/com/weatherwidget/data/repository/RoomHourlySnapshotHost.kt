package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.history.HourlySnapshotRun

/**
 * Android side of [HourlySnapshotRun] for one source at one site. Its database read is pinned to
 * desktop's by `HourlySnapshotHostContract`.
 */
internal class RoomHourlySnapshotHost(
    override val sourceId: String,
    private val latitude: Double,
    private val longitude: Double,
    private val historyDao: HourlyForecastHistoryDao,
    private val appLogDao: AppLogDao,
) : HourlySnapshotRun.Host {
    override suspend fun lastSnapshotMs() = historyDao.getLatestSnapshotLabel(latitude, longitude, sourceId)

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)

    companion object {
        /** The production label function for [HourlyForecastStore]. */
        fun labeler(
            historyDao: HourlyForecastHistoryDao,
            appLogDao: AppLogDao,
        ): suspend (String, Double, Double, Long) -> Long? = { sourceId, latitude, longitude, nowMs ->
            HourlySnapshotRun.run(RoomHourlySnapshotHost(sourceId, latitude, longitude, historyDao, appLogDao), nowMs).labelMs
        }
    }
}
