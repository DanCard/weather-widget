package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.remote.QuotaState
import com.weatherwidget.data.remote.RateLimitNotice

/**
 * [QuotaState] in `app_logs` contract rows ([ProductQuotaLog], [RateLimitLog]): the daemon writes
 * them and the popup, another process, reads them back. A block's [QuotaState.ProductQuota.sinceMs]
 * is its row's time — rows are written on change only ([QuotaState.record]).
 */
class DesktopQuotaState(private val weatherDao: DesktopWeatherDao) : QuotaState {
    override fun productQuota(sourceId: String, product: ForecastProduct, nowMs: Long): QuotaState.ProductQuota? {
        val (loggedAt, message) = weatherDao.getLatestProductQuota(sourceId, product) ?: return null
        val untilMs = ProductQuotaLog.parseUntilMs(message).takeIf { it > nowMs } ?: return null
        return QuotaState.ProductQuota(untilMs, loggedAt, ProductQuotaLog.parseDetail(message).ifEmpty { null })
    }

    override suspend fun recordProductQuota(sourceId: String, product: ForecastProduct, untilMs: Long, detail: String?) =
        weatherDao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked(sourceId, product, untilMs, detail.orEmpty()), "WARN")

    override suspend fun clearProductQuota(sourceId: String, product: ForecastProduct) =
        weatherDao.log(ProductQuotaLog.TAG, ProductQuotaLog.cleared(sourceId, product), "INFO")

    override suspend fun recordPartialRateLimit(sourceId: String, detail: String?) =
        weatherDao.log(RateLimitLog.TAG, RateLimitLog.partial(sourceId, detail.orEmpty()), "WARN")

    override fun partialRateLimit(sourceId: String, nowMs: Long): QuotaState.RateLimit? {
        val (loggedAt, message) = weatherDao.getLatestRateLimit(sourceId) ?: return null
        if (!RateLimitNotice.isShowing(loggedAt, nowMs)) return null
        return QuotaState.RateLimit(loggedAt, RateLimitLog.parseDetail(message).ifEmpty { null })
    }
}
