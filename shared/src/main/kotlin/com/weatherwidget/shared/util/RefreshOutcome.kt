package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.QuotaNoticeText
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.data.remote.RateLimitNoticeText

/**
 * What a Forecast History Refresh did, for the message when it finishes — both platforms. A fetch
 * with one product refused still "answered" (the other was saved), so "Refreshed Google" alone was
 * shown over a 429'd hourly forecast (Fold 4, 2026-10-16). Android maps it to localized strings;
 * desktop uses [text]. See plans/261016-history-refresh-outcome-shared-desktop-parity.md.
 */
sealed interface RefreshOutcome {
    data object Failed : RefreshOutcome

    data object Refreshed : RefreshOutcome

    /** Saved, but [scope] is quota-blocked until [untilMs], so that part is not new. */
    data class Refused(val scope: QuotaScope, val untilMs: Long) : RefreshOutcome

    /** Saved, but a per-minute 429 cut this refresh's hour pages short. */
    data object HourlyIncomplete : RefreshOutcome

    companion object {
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
        ): RefreshOutcome = when {
            !ok -> Failed
            hourlyBlockedUntilMs != null && dailyBlockedUntilMs != null ->
                Refused(QuotaScope.SOURCE, minOf(hourlyBlockedUntilMs, dailyBlockedUntilMs))
            hourlyBlockedUntilMs != null -> Refused(QuotaScope.HOURLY_FORECAST, hourlyBlockedUntilMs)
            dailyBlockedUntilMs != null -> Refused(QuotaScope.DAILY_FORECAST, dailyBlockedUntilMs)
            partialRateLimitAtMs != null && partialRateLimitAtMs >= startedAtMs -> HourlyIncomplete
            else -> Refreshed
        }

        /** The `HISTORY_REFRESH_DONE` detail: ` refused=HOURLY_FORECAST until=…`, ` refused=HOURLY_PARTIAL`, or "". */
        fun logDetail(outcome: RefreshOutcome): String = when (outcome) {
            is Refused -> " refused=${outcome.scope} until=${outcome.untilMs}"
            HourlyIncomplete -> " refused=HOURLY_PARTIAL"
            else -> ""
        }

        /** Desktop text (English); Android's strings are the same sentences, translated. */
        fun text(outcome: RefreshOutcome, sourceName: String, formatTime: (Long) -> String): String {
            if (outcome == Failed) return "Couldn't refresh $sourceName"
            val refreshed = "Refreshed $sourceName"
            return when (outcome) {
                is Refused -> "$refreshed · ${QuotaNoticeText.summary(outcome.scope, formatTime(outcome.untilMs))}"
                HourlyIncomplete -> "$refreshed · ${RateLimitNoticeText.short(partial = true)}"
                else -> refreshed
            }
        }
    }
}
