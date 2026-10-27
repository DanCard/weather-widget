package com.weatherwidget.data.remote

/**
 * Google `history/hours` at one site, as both platforms run it: whether a fetch requests it
 * ([needsHistory]). The rule is [GoogleWeatherApi.needsHistory]; the platform ([Host]) only reads
 * Google's rows at the site.
 *
 * The previous-day refill that also lived here was removed 2026-10-27: every started hour freezes at
 * its pre-hour forecast, so a post-hour rewrite never drew, while each one spent the 20/day quota
 * (plans/261027-remove-google-history-refill.md).
 */
class GoogleHistoryRun(private val host: Host) {
    interface Host {
        /** Hours with a Google `hourly_forecast_history` row at the site (same-site match) in [startMs, endMs). */
        suspend fun coveredHistoryHours(startMs: Long, endMs: Long): Collection<Long>
    }

    /** Whether this fetch requests `history/hours`: only a site with no history hours in the last day. */
    suspend fun needsHistory(nowMs: Long): Boolean {
        val window = GoogleWeatherApi.historyWindow(nowMs)
        return GoogleWeatherApi.needsHistory(host.coveredHistoryHours(window.first, window.last + 1), nowMs)
    }
}
