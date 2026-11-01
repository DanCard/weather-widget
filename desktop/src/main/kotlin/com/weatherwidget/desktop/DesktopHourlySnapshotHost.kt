package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.history.HourlySnapshotRun

/**
 * Desktop side of [HourlySnapshotRun] for one source at one site. Its database read is pinned to
 * Android's by `HourlySnapshotHostContract`.
 */
internal class DesktopHourlySnapshotHost(
    private val weatherDao: DesktopWeatherDao,
    override val sourceId: String,
    private val latitude: Double,
    private val longitude: Double,
) : HourlySnapshotRun.Host {
    override suspend fun lastSnapshotMs() = weatherDao.getLatestHourlySnapshotLabel(latitude, longitude, sourceId)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
