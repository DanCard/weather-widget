package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.QuotaNoticeText
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.data.remote.RateLimitNoticeText
import com.weatherwidget.shared.graph.FailureBannerLayout

/**
 * What a refresh did, for the message when it finishes — both platforms. A fetch
 * with one product refused still "answered" (the other was saved), so "Google refreshed" alone was
 * shown over a 429'd hourly forecast (Fold 4, 2026-10-16). Android maps it to localized strings;
 * desktop uses [text]. See plans/261016-history-refresh-outcome-shared-desktop-parity.md.
 */
sealed interface RefreshOutcome {
    /**
     * The source did not answer: [errorCode] says why ([com.weatherwidget.data.remote.FetchErrorCode]),
     * [retryAtMs] when [SourceRetry] tries again — "Meteo: Timed Out · retrying 9:20 PM". Either may be
     * unknown (plans/261112-refresh-failure-reason-and-retry.md).
     */
    data class Failed(val errorCode: String? = null, val retryAtMs: Long? = null) : RefreshOutcome

    data object Refreshed : RefreshOutcome

    /** Saved, but [scope] is quota-blocked until [untilMs], so that part is not new. */
    data class Refused(val scope: QuotaScope, val untilMs: Long) : RefreshOutcome

    /** Saved, but a per-minute 429 cut this refresh's hour pages short. */
    data object HourlyIncomplete : RefreshOutcome

    companion object {
        /** The refresh finished: [doneLine] at [doneLevel], both platforms. */
        const val DONE_TAG = "HISTORY_REFRESH_DONE"

        /** `ok=… <tap> ms=…[ refused=…]`; [tap] names the source (Android adds date and site). */
        fun doneLine(ok: Boolean, tap: String, ms: Long, outcome: RefreshOutcome): String =
            "ok=$ok $tap ms=$ms${logDetail(outcome)}"

        fun doneLevel(ok: Boolean): String = if (ok) "INFO" else "WARN"

        /**
         * An active product block means that product was not refreshed, whether this request was
         * refused or never asked (a product that answers clears its block). A partial rate limit counts
         * only when recorded during this refresh ([startedAtMs]); a quota outranks it.
         */
        fun of(
            ok: Boolean,
            hourlyBlockedUntilMs: Long?,
            dailyBlockedUntilMs: Long?,
            partialRateLimitAtMs: Long?,
            startedAtMs: Long,
            failureCode: String? = null,
            retryAtMs: Long? = null,
        ): RefreshOutcome = when {
            !ok -> Failed(failureCode, retryAtMs)
            hourlyBlockedUntilMs != null && dailyBlockedUntilMs != null ->
                Refused(QuotaScope.SOURCE, minOf(hourlyBlockedUntilMs, dailyBlockedUntilMs))
            hourlyBlockedUntilMs != null -> Refused(QuotaScope.HOURLY_FORECAST, hourlyBlockedUntilMs)
            dailyBlockedUntilMs != null -> Refused(QuotaScope.DAILY_FORECAST, dailyBlockedUntilMs)
            partialRateLimitAtMs != null && partialRateLimitAtMs >= startedAtMs -> HourlyIncomplete
            else -> Refreshed
        }

        /** The [DONE_TAG] detail: ` refused=HOURLY_FORECAST until=…`, ` refused=HOURLY_PARTIAL`, ` code=TIMEOUT retryAt=…`, or "". */
        fun logDetail(outcome: RefreshOutcome): String = when (outcome) {
            is Refused -> " refused=${outcome.scope} until=${outcome.untilMs}"
            is Failed ->
                (outcome.errorCode?.let { " code=$it" } ?: "") + (outcome.retryAtMs?.let { " retryAt=$it" } ?: "")
            HourlyIncomplete -> " refused=HOURLY_PARTIAL"
            else -> ""
        }

        /** Desktop text (English); Android's strings are the same sentences, translated. */
        fun text(
            outcome: RefreshOutcome,
            sourceName: String,
            formatTime: (Long) -> String,
            errorCodeText: (String) -> String = FailureBannerLayout::humanReadableErrorCode,
        ): String {
            if (outcome is Failed) {
                return failedText(
                    outcome,
                    sourceName,
                    couldNotRefresh = { "Couldn't refresh $it" },
                    errorCodeText = errorCodeText,
                    retrying = { "${RETRYING_EN} ${formatTime(it)}" },
                )
            }
            val refreshed = "$sourceName refreshed"
            return when (outcome) {
                is Refused -> "$refreshed · ${QuotaNoticeText.summary(outcome.scope, formatTime(outcome.untilMs))}"
                HourlyIncomplete -> "$refreshed · ${RateLimitNoticeText.short(partial = true)}"
                else -> refreshed
            }
        }

        /**
         * One wording for a failure, both platforms (Android passes its localized pieces):
         * "Meteo: Timed Out · retrying 9:20 PM", "Meteo: Timed Out", or "Couldn't refresh Meteo" when
         * the reason is unknown.
         */
        fun failedText(
            outcome: Failed,
            sourceName: String,
            couldNotRefresh: (String) -> String,
            errorCodeText: (String) -> String,
            retrying: (Long) -> String,
        ): String {
            val head = outcome.errorCode?.let { "$sourceName: ${errorCodeText(it)}" } ?: couldNotRefresh(sourceName)
            return outcome.retryAtMs?.let { "$head · ${retrying(it)}" } ?: head
        }

        /** English "retrying 9:20 PM" prefix (Android: `refresh_retrying_at`). */
        const val RETRYING_EN = "retrying"
    }
}
