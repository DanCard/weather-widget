package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.ZoneId

/**
 * Google's per-project daily quotas (`forecast/hours`, `history/hours`, …) reset at midnight Pacific.
 * A 429 against one of them cannot succeed again before then, unlike a per-minute 429 — the body's
 * ErrorInfo tells them apart by `quota_unit` (`1/d/{project}` vs `1/min/{project}`).
 */
object GoogleQuota {
    /** Error code the widget watermark keys on; the reset time is derived, not stored. */
    const val ERROR_CODE_DAILY = "QUOTA_DAILY"

    /**
     * Error codes for one refused forecast product while the rest of the source still updates
     * (user, 2026-10-07): the hourly views show the first, the daily view the second.
     */
    const val ERROR_CODE_HOURLY_FORECAST = "QUOTA_HOURLY_FORECAST"
    const val ERROR_CODE_DAILY_FORECAST = "QUOTA_DAILY_FORECAST"

    /** Every code whose detail line is "… quota used · resets <time>". */
    val QUOTA_CODES = setOf(ERROR_CODE_DAILY, ERROR_CODE_HOURLY_FORECAST, ERROR_CODE_DAILY_FORECAST)

    fun errorCodeFor(product: ForecastProduct): String = when (product) {
        ForecastProduct.HOURLY -> ERROR_CODE_HOURLY_FORECAST
        ForecastProduct.DAILY -> ERROR_CODE_DAILY_FORECAST
    }

    private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    private val DAILY_UNIT = Regex(""""quota_unit"\s*:\s*"1/d/""")

    fun isDailyQuotaExhausted(statusCode: Int?, detail: String?): Boolean =
        statusCode == 429 && detail != null && DAILY_UNIT.containsMatchIn(detail)

    fun isDailyQuotaExhausted(error: Throwable?): Boolean =
        error is GoogleDailyQuotaException ||
            (error is ApiAccessException && isDailyQuotaExhausted(error.statusCode, error.detail))

    private val METRIC = Regex(""""quota_metric"\s*:\s*"weather\.googleapis\.com/([A-Za-z/:]+)"""")

    /**
     * Which forecast product a stored 429 [detail] refused, from its `quota_metric`
     * (`weather.googleapis.com/forecast/hours` / `forecast/days`, verified on real bodies 2026-10-06/07).
     * Null for other quotas (history, current conditions) or a body without the metric. The API client
     * does not need this — it knows which endpoint it called — but stored failures are presented
     * from their detail text.
     */
    fun forecastProductOf(detail: String?): ForecastProduct? =
        when (detail?.let { METRIC.find(it)?.groupValues?.get(1) }) {
            "forecast/hours" -> ForecastProduct.HOURLY
            "forecast/days" -> ForecastProduct.DAILY
            else -> null
        }

    /** The first midnight Pacific after [nowMs]. */
    fun nextResetMs(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZONE).toLocalDate().plusDays(1)
            .atStartOfDay(ZONE).toInstant().toEpochMilli()
}

/**
 * Thrown without a request while a daily quota [GoogleWeatherApi] already hit is exhausted. Carries the
 * original 429 body so every consumer that classifies the failure sees the same thing it saw then.
 */
class GoogleDailyQuotaException(
    val resetAtMs: Long,
    detail: String,
) : ApiAccessException(
    source = WeatherSource.GOOGLE_WEATHER,
    statusCode = 429,
    detail = detail,
    // Same "status 429. Detail:" shape as require2xx, which readers of logged messages parse.
    message = "Google Weather fetch skipped until ${Instant.ofEpochMilli(resetAtMs)} (daily quota): status 429. Detail: $detail",
)
