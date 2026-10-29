package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.ChargeHistoryStore
import com.weatherwidget.shared.util.PreUnplugFetchRunner

/**
 * The daemon's [PreUnplugFetchRunner.Host] (plans/261028-learned-unplug-prediction.md): a file-backed
 * history, no clock alarms, and the forecast loop's wake as the check — the loop plans with the
 * prediction as its `wakeAtMs`, so waking it to re-plan is all a moved check needs.
 */
internal class DesktopPreUnplugHost(
    private val weatherDao: DesktopWeatherDao,
    override val store: ChargeHistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wake: (String) -> Unit,
) : PreUnplugFetchRunner.Host {
    override fun nowMs(): Long = clock()

    override fun nextAlarmMs(): Long? = null

    override fun scheduleCheck(delayMs: Long) = wake("pre_unplug")

    override fun cancelCheck() = wake("pre_unplug_cancelled")

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
