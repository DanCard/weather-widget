package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate

/**
 * Google `history/hours` at one site, as both platforms run it: whether a fetch requests it
 * ([needsHistory]) and writing it over a previous day's stale live hours ([refill]). The rules are
 * [GoogleWeatherApi.needsHistory] and [GoogleHistoryRefill]; the platform ([Host]) only reads and
 * writes Google's rows at the site.
 */
class GoogleHistoryRun(private val host: Host) {
    interface Host {
        /** Hours with a Google `hourly_forecast_history` row at the site (same-site match) in [startMs, endMs). */
        suspend fun coveredHistoryHours(startMs: Long, endMs: Long): Collection<Long>

        /** Google's live `hourly_forecasts` rows at the site (same-site match) in [startMs]..[endMs]. */
        suspend fun liveHours(startMs: Long, endMs: Long): List<HourlyForecast>

        /** Writes [rows] over the live rows of their hours; history snapshots untouched. */
        suspend fun saveRefill(rows: List<HourlyForecast>)

        suspend fun log(tag: String, message: String, level: String)
    }

    /**
     * Whether this fetch requests `history/hours`: a site with no history hours in the last day, or a
     * previous day's History refresh ([refillDay]) with stale hours. Any other fetch leaves stale hours
     * and logs `reason=not_requested` — the endpoint's daily quota is small.
     */
    suspend fun needsHistory(refillDay: LocalDate?, nowMs: Long): Boolean {
        val window = GoogleWeatherApi.historyWindow(nowMs)
        val needsHistory = GoogleWeatherApi.needsHistory(host.coveredHistoryHours(window.first, window.last + 1), nowMs)
        val stale = staleHours(refillDay, nowMs)
        if (refillDay == null && !needsHistory && stale.isNotEmpty()) {
            host.log(GoogleHistoryRefill.LOG_TAG, "stale=${stale.size} reason=not_requested", "VERBOSE")
        }
        return GoogleHistoryRefill.shouldRequest(needsHistory, stale, refillDay)
    }

    /** The live hours inside [GoogleWeatherApi.historyWindow] (and [day], when given) gone stale. */
    suspend fun staleHours(day: LocalDate?, nowMs: Long): Set<Long> {
        val window = GoogleWeatherApi.historyWindow(nowMs)
        return GoogleHistoryRefill.staleHours(host.liveHours(window.first, window.last), nowMs, day)
    }

    /** A previous [day]'s History refresh: [history] written over that day's stale live hours. */
    suspend fun refill(history: List<HourlyForecast>, day: LocalDate, nowMs: Long) {
        if (history.isEmpty()) return
        val stale = staleHours(day, nowMs)
        if (stale.isEmpty()) return
        val refill = GoogleHistoryRefill.select(history, stale)
        if (refill.isNotEmpty()) host.saveRefill(refill)
        host.log(GoogleHistoryRefill.LOG_TAG, GoogleHistoryRefill.logMessage(day, stale, refill), "INFO")
    }
}
