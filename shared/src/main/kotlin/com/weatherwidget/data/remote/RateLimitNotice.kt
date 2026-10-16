package com.weatherwidget.data.remote

/**
 * A provider's short-window quota refusal — an HTTP 429 that is not a daily quota (Google's
 * `ForecastHoursQueriesPerUser`, 10–20 per minute). Both platforms banner it at once on every view,
 * for [SHOW_MS] after the refusal: a per-minute limit is over by then (user, 2026-10-16). A daily
 * quota keeps its own notice ([QuotaNotice]), shown until it resets.
 * `plans/261016-quota-refusals-always-show-a-banner.md`
 */
object RateLimitNotice {
    /** How long the banner shows after the refusal. */
    const val SHOW_MS = 60_000L

    /** The whole fetch was refused. */
    const val ERROR_CODE = "RATE_LIMITED"

    /** A later `forecast/hours` page was refused; the pages before it were kept. */
    const val ERROR_CODE_PARTIAL = "RATE_LIMITED_PARTIAL"

    fun errorCode(partial: Boolean): String = if (partial) ERROR_CODE_PARTIAL else ERROR_CODE

    fun isErrorCode(code: String?): Boolean = code == ERROR_CODE || code == ERROR_CODE_PARTIAL

    fun isRateLimit(statusCode: Int?, detail: String?): Boolean =
        statusCode == 429 && !GoogleQuota.isDailyQuotaExhausted(statusCode, detail)

    fun isRateLimit(error: Throwable?): Boolean =
        error is ApiAccessException && error !is GoogleDailyQuotaException && isRateLimit(error.statusCode, error.detail)

    /** Whether a refusal at [atMs] is still bannered at [nowMs] (a clock stepped back still shows it). */
    fun isShowing(atMs: Long?, nowMs: Long): Boolean = atMs != null && nowMs - atMs < SHOW_MS

    /** Milliseconds until a banner for a refusal at [atMs] comes down, or null when it already has. */
    fun remainingMs(atMs: Long?, nowMs: Long): Long? =
        atMs?.let { it + SHOW_MS - nowMs }?.takeIf { it > 0L }
}

/** The English copy (Android's `strings.xml` mirrors it: `updates_rate_limited`, `watermark_rate_limited*`). */
object RateLimitNoticeText {
    fun headline(sourceDisplayName: String): String = "${sourceDisplayName.uppercase()} RATE LIMITED"

    fun short(partial: Boolean): String = if (partial) "Hourly forecast incomplete" else "Too many requests"
}
