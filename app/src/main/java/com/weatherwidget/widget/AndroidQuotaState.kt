package com.weatherwidget.widget

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.desktop.ProductQuotaLog
import com.weatherwidget.data.local.desktop.RateLimitLog
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.QuotaState

/** [QuotaState] in the widget's prefs ([WidgetFetchStateStore]), logged in the same row formats as desktop. */
internal class AndroidQuotaState(
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
) : QuotaState {
    override fun productQuota(sourceId: String, product: ForecastProduct, nowMs: Long) =
        widgetStateManager.getProductQuota(WeatherSource.fromId(sourceId), product, nowMs)
            ?.let { QuotaState.ProductQuota(it.untilMs, it.sinceMs, it.detail) }

    override suspend fun recordProductQuota(sourceId: String, product: ForecastProduct, untilMs: Long, detail: String?) {
        widgetStateManager.recordProductQuota(WeatherSource.fromId(sourceId), product, untilMs, detail)
        appLogDao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked(sourceId, product, untilMs, detail.orEmpty()), "WARN")
    }

    override suspend fun clearProductQuota(sourceId: String, product: ForecastProduct) {
        widgetStateManager.clearProductQuota(WeatherSource.fromId(sourceId), product)
        appLogDao.log(ProductQuotaLog.TAG, ProductQuotaLog.cleared(sourceId, product), "INFO")
    }

    override suspend fun recordPartialRateLimit(sourceId: String, detail: String?) {
        widgetStateManager.recordRateLimit(WeatherSource.fromId(sourceId), partial = true, detail = detail)
        appLogDao.log(RateLimitLog.TAG, RateLimitLog.partial(sourceId, detail.orEmpty()), "WARN")
    }

    override fun partialRateLimit(sourceId: String, nowMs: Long) =
        widgetStateManager.getRateLimit(WeatherSource.fromId(sourceId), nowMs)
            ?.takeIf { it.partial }
            ?.let { QuotaState.RateLimit(it.atMs, it.detail) }
}
