package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.widget.ViewMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a daily-quota refusal stopped: the whole source, or one forecast product of it. */
enum class QuotaScope(val errorCode: String) {
    SOURCE(GoogleQuota.ERROR_CODE_DAILY),
    HOURLY_FORECAST(GoogleQuota.ERROR_CODE_HOURLY_FORECAST),
    DAILY_FORECAST(GoogleQuota.ERROR_CODE_DAILY_FORECAST),
    ;

    companion object {
        fun ofErrorCode(code: String?): QuotaScope? = entries.firstOrNull { it.errorCode == code }

        fun of(product: ForecastProduct): QuotaScope = when (product) {
            ForecastProduct.HOURLY -> HOURLY_FORECAST
            ForecastProduct.DAILY -> DAILY_FORECAST
        }
    }
}

/**
 * A daily-quota refusal as both platforms present it: which [scope] it covers, when it resets, and the
 * provider's 429 body. Android (widget pill, error-details screen) and desktop (popup banner) both build
 * it here so they agree on which view shows which quota and on the reset time; each turns it into text
 * its own way (Android from localized resources, desktop from [QuotaNoticeText]).
 */
data class QuotaNotice(
    val scope: QuotaScope,
    val resetAtMs: Long,
    /** The stored 429 body (or the failure message carrying it), for [provider]. */
    val detail: String?,
) {
    val provider: ProviderErrorDetails? get() = ProviderErrorDetails.parse(detail)

    companion object {
        /**
         * A source-wide failure recorded with [errorCode] at [failureMs]; null when it is not a quota.
         * The reset follows the failure, not the clock: a refusal from before midnight Pacific read
         * after it has already reset (desktop used `now`, which named tomorrow's reset).
         */
        fun forSourceFailure(errorCode: String?, failureMs: Long?, detail: String?): QuotaNotice? {
            val scope = QuotaScope.ofErrorCode(errorCode) ?: return null
            return QuotaNotice(scope, GoogleQuota.nextResetMs(failureMs ?: return null), detail)
        }

        /**
         * A source-wide failure known only by its HTTP [statusCode] and [detail] (desktop's stored
         * status rows): the same classification Android applies when it records [QuotaScope.SOURCE].
         */
        fun forSourceFailure(statusCode: Int?, failureMs: Long, detail: String?): QuotaNotice? =
            if (GoogleQuota.isDailyQuotaExhausted(statusCode, detail)) {
                QuotaNotice(QuotaScope.SOURCE, GoogleQuota.nextResetMs(failureMs), detail)
            } else {
                null
            }

        /** One forecast product refused until [untilMs] while the source still updates. */
        fun forProductBlock(product: ForecastProduct, untilMs: Long, detail: String?): QuotaNotice =
            QuotaNotice(QuotaScope.of(product), untilMs, detail)

        /**
         * The forecast product a view draws, and so the only product block it shows (user,
         * 2026-10-07): the daily view the daily forecast, every hourly graph the hourly forecast.
         * A source-wide failure is shown on every view and takes precedence over either.
         */
        fun productOf(view: ViewMode): ForecastProduct =
            if (view == ViewMode.DAILY) ForecastProduct.DAILY else ForecastProduct.HOURLY
    }
}

/**
 * The English copy for a [QuotaNotice]. Matches Android's default `strings.xml` (`updates_paused`,
 * `watermark_quota_*`, `error_details_explain_quota`, `error_details_limit_per_day`), which stay the
 * source of the localized Android text.
 */
object QuotaNoticeText {
    fun headline(sourceDisplayName: String, locale: Locale = Locale.getDefault()): String =
        "${sourceDisplayName.uppercase(locale)} UPDATES PAUSED"

    fun short(scope: QuotaScope): String = when (scope) {
        QuotaScope.SOURCE -> "Daily quota used"
        QuotaScope.HOURLY_FORECAST -> "Hourly forecast quota used"
        QuotaScope.DAILY_FORECAST -> "Daily forecast quota used"
    }

    /** "<which> quota used · resets <time>" — the widget pill's detail line. */
    fun summary(scope: QuotaScope, resetTime: String): String = "${short(scope)} · resets $resetTime"

    fun explanation(resetTime: String): String =
        "The daily request quota for this API key is used up. Cached weather is still shown; " +
            "updates resume after the quota resets at $resetTime."

    /** "Quota: … — 60 per day, shared by every device using this key" and "Request: …", when known. */
    fun detailLines(provider: ProviderErrorDetails?): List<String> = listOfNotNull(
        provider?.quotaName?.let { name ->
            "Quota: $name" + (provider.quotaLimit?.let { " — ${limitText(it, provider.quotaPeriod)}" } ?: "")
        },
        provider?.request?.let { "Request: $it" },
    )

    fun limitText(limit: String, period: ProviderErrorDetails.QuotaPeriod?): String = when (period) {
        ProviderErrorDetails.QuotaPeriod.DAY -> "$limit per day, shared by every device using this key"
        ProviderErrorDetails.QuotaPeriod.MINUTE -> "$limit per minute"
        null -> limit
    }

    /** "12 AM" on the hour, else "12:30 AM" — the reset is always a whole hour in practice. */
    fun formatResetTime(epochMs: Long, locale: Locale = Locale.getDefault(), zoneId: ZoneId = ZoneId.systemDefault()): String {
        val reset = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val pattern = if (reset.minute == 0) "h a" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(reset)
    }
}
