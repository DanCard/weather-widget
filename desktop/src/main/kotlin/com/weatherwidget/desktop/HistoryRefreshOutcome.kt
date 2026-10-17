package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.ProductQuotaLog
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.shared.util.RefreshOutcome

/**
 * What a Forecast History Refresh of [sourceId] did ([RefreshOutcome.of]), read from the rows the
 * refresh itself writes: `PRODUCT_QUOTA` (active while `until > now`; a product that answered logs
 * `until=0`) and `FETCH_RATE_LIMITED` (partial refusals only). [ok] = the refresh did not throw — a
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
    fun blockedUntil(product: ForecastProduct): Long? =
        weatherDao.getLatestProductQuota(sourceId, product)
            ?.let { ProductQuotaLog.parseUntilMs(it.second) }
            ?.takeIf { it > nowMs }
    return RefreshOutcome.of(
        ok = true,
        hourlyBlockedUntilMs = blockedUntil(ForecastProduct.HOURLY),
        dailyBlockedUntilMs = blockedUntil(ForecastProduct.DAILY),
        partialRateLimitAtMs = weatherDao.getLatestRateLimit(sourceId)?.first,
        startedAtMs = startedAtMs,
    )
}
