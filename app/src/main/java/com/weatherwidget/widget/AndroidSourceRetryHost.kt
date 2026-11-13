package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.SourceRetry

/**
 * Android's [SourceRetry.Host]: a source is displayed when a widget shows it; the pending retry lives in
 * widget-state prefs (a success clears it) and wakes as a delayed sync targeted at that source
 * ([WidgetWorkScheduler.enqueueSourceRetrySync]).
 */
internal class AndroidSourceRetryHost(
    private val context: Context,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    /** (charging, screen on) now. */
    private val power: () -> Pair<Boolean, Boolean> = {
        DataFreshness.deviceFetchContext(context, widgetStateManager).let { it.isCharging to it.isScreenInteractive }
    },
    private val enqueueRetry: (sourceId: String, delayMs: Long) -> Unit = { sourceId, delayMs ->
        WidgetWorkScheduler.enqueueSourceRetrySync(context, sourceId, delayMs)
    },
) : SourceRetry.Host {
    override val isCharging: Boolean get() = power().first

    override val isScreenOn: Boolean get() = power().second

    override fun isDisplayed(sourceId: String): Boolean = sourceId in widgetStateManager.getActiveDisplaySourceIds()

    override fun pending(sourceId: String): SourceRetry.Pending? =
        widgetStateManager.getSourceRetry(WeatherSource.fromId(sourceId))

    override suspend fun arm(sourceId: String, retry: SourceRetry.Pending) {
        widgetStateManager.armSourceRetry(WeatherSource.fromId(sourceId), retry)
        enqueueRetry(sourceId, retry.delayMs)
    }

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}
