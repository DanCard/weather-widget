package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.CurrentTempStatus
import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.remote.FetchErrorCode
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.data.remote.SourceErrorDetails

/** What the shared failure pill ([com.weatherwidget.shared.graph.FailureBannerLayout]) is worded from. */
internal data class DesktopFailurePill(
    val sourceLabel: String,
    val errorCode: String?,
    val failureTimeMs: Long,
)

/**
 * A stored current-temp failure, classified as Android records it ([FetchErrorCode.fromLogged]):
 * a daily quota is bannered on every view until it resets, a per-minute 429 on every view for a minute.
 */
internal data class DesktopFailureKind(val code: String, val quota: Boolean, val rateLimit: Boolean)

internal fun desktopFailureKind(status: CurrentTempStatus): DesktopFailureKind {
    val code = FetchErrorCode.fromLogged(
        CurrentTempStatusLog.parseFailureClassName(status.message),
        CurrentTempStatusLog.parseFailureDetail(status.message),
    )
    return DesktopFailureKind(code, quota = QuotaScope.ofErrorCode(code) != null, rateLimit = code == "HTTP_429")
}

/**
 * What the details card shows for the pill on screen — the shared [SourceErrorDetails] rule over
 * desktop's stores. [sourceFailure] is the failed status row the pill shows; null when the pill is a
 * product quota block or a partial rate limit, so the card describes that and not an older failure.
 */
internal fun desktopSourceErrorDetails(
    weatherDao: DesktopWeatherDao,
    sourceId: String,
    sourceFailure: CurrentTempStatus?,
    viewed: ForecastProduct,
    nowMs: Long,
): SourceErrorDetails.Content? {
    val quotas = DesktopQuotaState(weatherDao)
    val facts = SourceErrorDetails.Facts(
        failureCount = if (sourceFailure != null) weatherDao.getCurrentTempFailureStreak(sourceId).coerceAtLeast(1) else 0,
        lastErrorCode = sourceFailure?.let { desktopFailureKind(it).code },
        lastFailureMs = sourceFailure?.timestamp,
        lastFailureDetail = sourceFailure?.let { CurrentTempStatusLog.parseFailureDetail(it.message) },
        productQuota = { product -> quotas.productQuota(sourceId, product, nowMs) },
        rateLimit = quotas.partialRateLimit(sourceId, nowMs)?.let { SourceErrorDetails.RateLimit(it.atMs, partial = true, detail = it.detail) },
        lastSuccessMs = weatherDao.getLastSuccessfulFetch(sourceId),
    )
    return SourceErrorDetails.build(facts, viewed, nowMs)
}
