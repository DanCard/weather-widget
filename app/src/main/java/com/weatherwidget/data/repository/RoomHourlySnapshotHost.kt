package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.shared.history.HourlySnapshotRun
import com.weatherwidget.widget.BatterySnapshotProvider
import com.weatherwidget.widget.SourceFetchGateLoader
import com.weatherwidget.widget.WidgetStateManager

/**
 * Android side of [HourlySnapshotRun] for one source at one site: displayed = on any widget, viewed
 * recently = [SourceFetchGateLoader]'s background set (fails open), battery from
 * [BatterySnapshotProvider]. Its database read is pinned to desktop's by `HourlySnapshotHostContract`.
 */
internal class RoomHourlySnapshotHost(
    override val sourceId: String,
    private val latitude: Double,
    private val longitude: Double,
    private val historyDao: HourlyForecastHistoryDao,
    private val appLogDao: AppLogDao,
    private val isDisplayed: suspend () -> Boolean,
    private val viewedRecently: suspend () -> Boolean,
    private val batteryHigh: suspend () -> Boolean,
) : HourlySnapshotRun.Host {
    override suspend fun isDisplayed() = isDisplayed.invoke()

    override suspend fun viewedRecently() = viewedRecently.invoke()

    override suspend fun batteryHigh() = batteryHigh.invoke()

    override suspend fun lastSnapshotMs() = historyDao.getLatestSnapshotLabel(latitude, longitude, sourceId)

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)

    companion object {
        /** The production label function for [HourlyForecastStore]: the Host above over the app's own state. */
        fun labeler(
            context: Context,
            widgetStateManager: WidgetStateManager,
            historyDao: HourlyForecastHistoryDao,
            appLogDao: AppLogDao,
        ): suspend (String, Double, Double, Long) -> Long? = { sourceId, latitude, longitude, nowMs ->
            HourlySnapshotRun.run(
                RoomHourlySnapshotHost(
                    sourceId, latitude, longitude, historyDao, appLogDao,
                    isDisplayed = { sourceId in widgetStateManager.getActiveDisplaySourceIds() },
                    viewedRecently = {
                        SourceFetchGateLoader.load(context, widgetStateManager, latitude, longitude, nowMs)
                            ?.forecasts?.any { it.id == sourceId } ?: true
                    },
                    batteryHigh = {
                        val battery = BatterySnapshotProvider.snapshot(context)
                        ForecastHorizonCadence.isHighBattery(battery.isCharging, battery.batteryLevel)
                    },
                ),
                nowMs,
            ).labelMs
        }
    }
}
