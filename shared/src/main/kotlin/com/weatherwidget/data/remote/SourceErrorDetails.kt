package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.shared.graph.FailureBannerLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a click on a failure or quota pill shows: which failure it is, the provider's own details and
 * the full response. One rule for the Android error page (`SourceErrorDetailsActivity`) and the desktop
 * popup's details card, so the page always describes the pill that opened it (user, 2026-10-27,
 * `plans/261027-error-and-quota-banner-click-shows-details-both-platforms.md`).
 *
 * [build] decides; it returns typed [Value]s, not text. Android turns them into its localized
 * resources; desktop uses [English], whose strings match Android's default `strings.xml`
 * (`SourceErrorDetailsEnglishParityTest` in `:app`).
 */
object SourceErrorDetails {
    /** A short-window refusal from the last minute: a whole fetch (`HTTP_429`) or later hourly pages. */
    data class RateLimit(val atMs: Long, val partial: Boolean, val detail: String?)

    /** What each platform knows about [source][Facts]'s failures; reads only, the rule is [build]. */
    data class Facts(
        /** Consecutive failed fetches; 0 when the last one succeeded. */
        val failureCount: Int,
        val lastErrorCode: String?,
        val lastFailureMs: Long?,
        val lastFailureDetail: String?,
        val productQuota: (ForecastProduct) -> QuotaState.ProductQuota?,
        val rateLimit: RateLimit?,
        val lastSuccessMs: Long?,
    )

    enum class Headline { PAUSED, RATE_LIMITED, FAILING }

    enum class Explanation { QUOTA, RATE_LIMIT, AUTH, DATA_RESTRICTED, GENERIC }

    enum class Label { ERROR, REQUEST, STATUS, MESSAGE, QUOTA, LIMIT, METRIC, WINDOW, RESETS, FAILURES, LAST_ATTEMPT, LAST_SUCCESS }

    sealed interface Value {
        data class Plain(val text: String) : Value
        data class ErrorCode(val code: String) : Value
        data class Limit(val limit: String, val period: ProviderErrorDetails.QuotaPeriod?) : Value
        data class DateTime(val epochMs: Long) : Value
        data class ResetTime(val epochMs: Long) : Value
        /** Today's time, else date and time ([FailureBannerLayout.formatFailureTime]). */
        data class Moment(val epochMs: Long) : Value
    }

    data class Row(val label: Label, val value: Value)

    sealed interface Summary {
        data class Quota(val scope: QuotaScope, val resetAtMs: Long) : Summary
        data class Code(val code: String) : Summary
        data object Failing : Summary
    }

    data class Content(
        val headline: Headline,
        val summary: Summary,
        val explanation: Explanation,
        /** Set with [Explanation.QUOTA]: the reset its text names. */
        val resetAtMs: Long?,
        val rows: List<Row>,
        val rawResponse: String?,
        val offerApiKeySettings: Boolean,
    )

    private val AUTH_CODES = setOf("HTTP_401", "HTTP_403", "ACCESS_ERROR")

    /**
     * The failure the pill for a view of [viewed] shows, or null when nothing is failing (the pill is
     * gone, or was clicked as it cleared). The pill's own order: a source-wide failure streak first,
     * else a product quota block — [viewed]'s, else the other's ([QuotaNotice.bannerBlock]) — else a
     * rate limit from the last minute.
     */
    fun build(facts: Facts, viewed: ForecastProduct?, nowMs: Long): Content? {
        val count = facts.failureCount
        val productBlock = if (count > 0) {
            null
        } else if (viewed != null) {
            QuotaNotice.bannerBlock(viewed, facts.productQuota)
        } else {
            ForecastProduct.entries.firstNotNullOfOrNull { product -> facts.productQuota(product)?.let { product to it } }
        }
        val rateLimit = if (count > 0 || productBlock != null) null else facts.rateLimit?.takeIf { RateLimitNotice.isShowing(it.atMs, nowMs) } ?: return null
        val code = productBlock?.let { GoogleQuota.errorCodeFor(it.first) }
            ?: rateLimit?.let { RateLimitNotice.errorCode(it.partial) }
            ?: facts.lastErrorCode
        val failedAt = productBlock?.second?.sinceMs ?: rateLimit?.atMs ?: facts.lastFailureMs
        val detail = productBlock?.second?.detail ?: rateLimit?.detail ?: facts.lastFailureDetail
        val provider = ProviderErrorDetails.parse(detail)
        val quota = productBlock
            ?.let { (product, block) -> QuotaNotice.forProductBlock(product, block.untilMs, detail) }
            ?: QuotaNotice.forSourceFailure(code, failedAt, detail)

        val rows = buildList {
            code?.let { add(Row(Label.ERROR, Value.ErrorCode(it))) }
            provider?.request?.let { add(Row(Label.REQUEST, Value.Plain(it))) }
            provider?.httpStatus?.let { add(Row(Label.STATUS, Value.Plain(it.toString()))) }
            provider?.providerMessage?.let { add(Row(Label.MESSAGE, Value.Plain(it))) }
            provider?.quotaName?.let { add(Row(Label.QUOTA, Value.Plain(it))) }
            provider?.quotaLimit?.let { add(Row(Label.LIMIT, Value.Limit(it, provider.quotaPeriod))) }
            provider?.quotaMetric?.let { add(Row(Label.METRIC, Value.Plain(it))) }
            provider?.quotaWindowStartMs?.let { add(Row(Label.WINDOW, Value.DateTime(it))) }
            quota?.let { add(Row(Label.RESETS, Value.ResetTime(it.resetAtMs))) }
            if (count > 0) add(Row(Label.FAILURES, Value.Plain(count.toString())))
            failedAt?.let { add(Row(Label.LAST_ATTEMPT, Value.Moment(it))) }
            facts.lastSuccessMs?.takeIf { it > 0L }?.let { add(Row(Label.LAST_SUCCESS, Value.Moment(it))) }
        }
        return Content(
            headline = when {
                quota != null -> Headline.PAUSED
                RateLimitNotice.isErrorCode(code) -> Headline.RATE_LIMITED
                else -> Headline.FAILING
            },
            summary = when {
                quota != null -> Summary.Quota(quota.scope, quota.resetAtMs)
                code != null -> Summary.Code(code)
                else -> Summary.Failing
            },
            explanation = when {
                quota != null -> Explanation.QUOTA
                code == "HTTP_429" || RateLimitNotice.isErrorCode(code) -> Explanation.RATE_LIMIT
                code in AUTH_CODES -> Explanation.AUTH
                code == "DATA_RESTRICTED" -> Explanation.DATA_RESTRICTED
                else -> Explanation.GENERIC
            },
            resetAtMs = quota?.resetAtMs,
            rows = rows,
            rawResponse = provider?.rawBody,
            offerApiKeySettings = code in AUTH_CODES,
        )
    }

    /** The English text, matching Android's default `strings.xml`; desktop shows it as is. */
    object English {
        const val NONE = "No failure is recorded for this source right now."
        const val SHOW_RESPONSE = "Show full response"
        const val HIDE_RESPONSE = "Hide full response"
        const val API_KEY_SETTINGS = "API key settings"

        fun headline(sourceDisplayName: String, headline: Headline, locale: Locale = Locale.getDefault()): String =
            "${sourceDisplayName.uppercase(locale)} " + when (headline) {
                Headline.PAUSED -> "UPDATES PAUSED"
                Headline.RATE_LIMITED -> "RATE LIMITED"
                Headline.FAILING -> "UPDATES FAILING"
            }

        fun summary(summary: Summary, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
            when (summary) {
                is Summary.Quota -> QuotaNoticeText.summary(summary.scope, QuotaNoticeText.formatResetTime(summary.resetAtMs, locale, zone))
                is Summary.Code -> FailureBannerLayout.humanReadableErrorCode(summary.code)
                Summary.Failing -> "UPDATES FAILING"
            }

        fun explanation(content: Content, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
            when (content.explanation) {
                Explanation.QUOTA -> QuotaNoticeText.explanation(QuotaNoticeText.formatResetTime(content.resetAtMs ?: 0L, locale, zone))
                Explanation.RATE_LIMIT ->
                    "The provider is limiting how often this app may ask for data. Cached weather is still shown; " +
                        "the next scheduled update will try again."
                Explanation.AUTH -> "The provider rejected the API key. Check the key in Settings."
                Explanation.DATA_RESTRICTED ->
                    "Android is blocking background data for this app, so updates only run while the app is open."
                Explanation.GENERIC ->
                    "Recent updates failed. Cached weather is still shown; the next scheduled update will try again."
            }

        fun label(label: Label): String = when (label) {
            Label.ERROR -> "Error"
            Label.REQUEST -> "Request"
            Label.STATUS -> "HTTP status"
            Label.MESSAGE -> "Provider message"
            Label.QUOTA -> "Quota"
            Label.LIMIT -> "Limit"
            Label.METRIC -> "Metric"
            Label.WINDOW -> "Quota window started"
            Label.RESETS -> "Resets"
            Label.FAILURES -> "Failures in a row"
            Label.LAST_ATTEMPT -> "Last attempt"
            Label.LAST_SUCCESS -> "Last successful update"
        }

        fun value(value: Value, nowMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
            when (value) {
                is Value.Plain -> value.text
                is Value.ErrorCode -> FailureBannerLayout.humanReadableErrorCode(value.code)
                is Value.Limit -> QuotaNoticeText.limitText(value.limit, value.period)
                is Value.DateTime -> formatDateTime(value.epochMs, locale, zone)
                is Value.ResetTime -> QuotaNoticeText.formatResetTime(value.epochMs, locale, zone)
                is Value.Moment -> FailureBannerLayout.formatFailureTime(value.epochMs, nowMs, locale, zone)
            }
    }

    /** "Oct 27, 2:15 PM": the quota window's start. */
    fun formatDateTime(epochMs: Long, locale: Locale, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
