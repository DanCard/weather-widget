package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopSourceRetryState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.SourceRetry

/**
 * Desktop's [SourceRetry.Host]: the daemon's displayed source only; armed as a `SOURCE_RETRY_ARMED` row
 * ([DesktopSourceRetryState]) that the forecast loop sleeps toward, woken by [wake] when the loop is not
 * the caller (plans/261112-refresh-failure-reason-and-retry.md).
 */
internal class DesktopSourceRetryHost(
    private val weatherDao: DesktopWeatherDao,
    private val displayedSource: () -> String?,
    private val charging: () -> Boolean,
    private val screenOn: () -> Boolean,
    private val wake: (() -> Unit)?,
) : SourceRetry.Host {
    private val state = DesktopSourceRetryState(weatherDao)

    override val isCharging: Boolean get() = charging()

    override val isScreenOn: Boolean get() = screenOn()

    override fun isDisplayed(sourceId: String) = displayedSource() == sourceId

    override fun pending(sourceId: String) = state.pending(sourceId)

    override suspend fun arm(sourceId: String, retry: SourceRetry.Pending) {
        state.arm(sourceId, retry)
        wake?.invoke()
    }

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
