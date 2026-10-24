package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.shared.history.HourlySnapshotRun

/**
 * Desktop side of [HourlySnapshotRun] for one source at one site: displayed = the popup's source in
 * [config], viewed recently = [DesktopSourceFetchGate]'s background set, battery from [PowerDetector].
 * A null [config] (tests, an unreadable config file) counts as displayed: save every fetch, as before.
 * Its database read is pinned to Android's by `HourlySnapshotHostContract`.
 */
internal class DesktopHourlySnapshotHost(
    private val weatherDao: DesktopWeatherDao,
    override val sourceId: String,
    private val latitude: Double,
    private val longitude: Double,
    private val config: DesktopConfig?,
    private val nowMs: Long,
) : HourlySnapshotRun.Host {
    override suspend fun isDisplayed() = config == null || config.displaySource == sourceId

    override suspend fun viewedRecently() =
        config == null || DesktopSourceFetchGate.load(weatherDao, config, nowMs)?.forecasts?.any { it.id == sourceId } ?: true

    override suspend fun batteryHigh(): Boolean {
        val power = PowerDetector.getPowerState()
        return ForecastHorizonCadence.isHighBattery(power.isCharging, power.batteryLevel)
    }

    override suspend fun lastSnapshotMs() = weatherDao.getLatestHourlySnapshotLabel(latitude, longitude, sourceId)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
