package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.local.desktop.DesktopSourceRetryState
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.shared.util.RefreshOutcome

/**
 * What a Forecast History Refresh of [sourceId] did ([RefreshOutcome.of]), read from the rows the
 * refresh itself writes, through the same [DesktopQuotaState] the popup banners from. [ok] = the refresh did not throw — a
 * failed one already wrote its `CURRENT_TEMP_STATUS ok=false` and rethrew. Android reads the same facts
 * from `WidgetStateManager` (plans/261016-history-refresh-outcome-shared-desktop-parity.md).
 */
internal fun historyRefreshOutcome(
    weatherDao: DesktopWeatherDao,
    sourceId: String,
    ok: Boolean,
    startedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): RefreshOutcome {
    if (!ok) return failedOutcome(weatherDao, sourceId, nowMs)
    val quotas = DesktopQuotaState(weatherDao)
    return RefreshOutcome.of(
        ok = true,
        hourlyBlockedUntilMs = quotas.productQuota(sourceId, ForecastProduct.HOURLY, nowMs)?.untilMs,
        dailyBlockedUntilMs = quotas.productQuota(sourceId, ForecastProduct.DAILY, nowMs)?.untilMs,
        partialRateLimitAtMs = quotas.partialRateLimit(sourceId, nowMs)?.atMs,
        startedAtMs = startedAtMs,
    )
}

/**
 * A failed refresh of [sourceId], with why (its newest failed `CURRENT_TEMP_STATUS` row) and when it is
 * retried ([DesktopSourceRetryState]): "Meteo: Timed Out · retrying 9:20 PM"
 * (plans/261112-refresh-failure-reason-and-retry.md).
 */
internal fun failedOutcome(weatherDao: DesktopWeatherDao, sourceId: String, nowMs: Long = System.currentTimeMillis()): RefreshOutcome.Failed =
    RefreshOutcome.Failed(
        errorCode = weatherDao.getLatestCurrentTempStatus(sourceId)?.takeIf { !it.ok }?.let { desktopFailureKind(it).code },
        retryAtMs = DesktopSourceRetryState(weatherDao).retryAtMs(sourceId, nowMs),
    )
