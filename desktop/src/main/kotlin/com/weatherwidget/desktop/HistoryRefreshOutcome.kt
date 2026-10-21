package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopQuotaState
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
    if (!ok) return RefreshOutcome.Failed
    val quotas = DesktopQuotaState(weatherDao)
    return RefreshOutcome.of(
        ok = true,
        hourlyBlockedUntilMs = quotas.productQuota(sourceId, ForecastProduct.HOURLY, nowMs)?.untilMs,
        dailyBlockedUntilMs = quotas.productQuota(sourceId, ForecastProduct.DAILY, nowMs)?.untilMs,
        partialRateLimitAtMs = quotas.partialRateLimit(sourceId, nowMs)?.atMs,
        startedAtMs = startedAtMs,
    )
}
