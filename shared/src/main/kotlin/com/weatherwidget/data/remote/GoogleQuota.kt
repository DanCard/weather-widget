package com.weatherwidget.data.remote

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

    private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    private val DAILY_UNIT = Regex(""""quota_unit"\s*:\s*"1/d/""")

    fun isDailyQuotaExhausted(statusCode: Int?, detail: String?): Boolean =
        statusCode == 429 && detail != null && DAILY_UNIT.containsMatchIn(detail)

    fun isDailyQuotaExhausted(error: Throwable?): Boolean =
        error is GoogleDailyQuotaException ||
            (error is ApiAccessException && isDailyQuotaExhausted(error.statusCode, error.detail))

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
