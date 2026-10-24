package com.weatherwidget.shared.nowcast

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.Log

/**
 * Open-Meteo's 15-minute nowcast refresh, one rule for both platforms: Android's current-temp run
 * and desktop's observation loop each wrote the gate inputs, the [NowcastRefreshPolicy] call and
 * the `NOWCAST_*` logs themselves (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 * Rides an existing fetch; adds no wakeup. `plans/261021-open-meteo-15-min-nowcast.md`.
 */
object NowcastRefreshRun {
    private const val TAG = "NowcastRefreshRun"
    private const val HOUR_MS = 60L * 60 * 1000

    interface Host {
        val lat: Double
        val lon: Double

        /** Open-Meteo is shown somewhere (a widget / the popup). */
        val openMeteoDisplayed: Boolean

        val store: NowcastStore

        /** Sources enabled and available at this location — the only ones whose rows count ([run]). */
        val usableSourceIds: Set<String>

        /** Every live `hourly_forecasts` row in the read box over [fromMs]..[toMs], all sources, uncollapsed. */
        suspend fun hourly(fromMs: Long, toMs: Long): List<HourlyForecast>

        /** The ~1 KB request; null when the platform cannot make it now. */
        suspend fun fetch(): NowcastFetcher.Outcome?

        suspend fun log(tag: String, message: String, level: String)
    }

    /** The fetch outcome, or null when skipped. */
    suspend fun run(host: Host, reason: String, nowMs: Long): NowcastFetcher.Outcome? {
        // Hourly is read only when it can matter: Open-Meteo not shown skips first. Only usable sources'
        // rows count (user, 2026-10-24): a source turned off, or the Generic gap filler, keeps stale
        // rows that must not trigger a request — Android counted every row in the box, desktop these.
        val hourly = if (host.openMeteoDisplayed) {
            host.hourly(nowMs - HOUR_MS, nowMs + NowcastRefreshPolicy.PLAUSIBLE_WINDOW_MS)
                .filter { it.source in host.usableSourceIds }
        } else {
            emptyList()
        }
        val maxPop = NowcastRefreshPolicy.maxPopNext3h(hourly, nowMs)
        val skip = NowcastRefreshPolicy.skipReason(
            openMeteoDisplayed = host.openMeteoDisplayed,
            nativeCoverage = host.store.loadCoverage(host.lat, host.lon, nowMs),
            cached = host.store.loadSeries(host.lat, host.lon, nowMs),
            maxPopNext3h = maxPop,
            nowMs = nowMs,
        )
        if (skip != null) {
            Log.d(TAG, "NOWCAST_SKIP reason=${skip.logName} trigger=$reason maxPop=$maxPop")
            return null
        }
        val outcome = host.fetch() ?: return null
        host.log(
            "NOWCAST_FETCH",
            "trigger=$reason maxPop=$maxPop ${outcome.logLine}",
            if (outcome is NowcastFetcher.Outcome.Failed) "WARN" else "INFO",
        )
        if (outcome is NowcastFetcher.Outcome.Fetched) {
            val summary = host.store.loadSeries(host.lat, host.lon, nowMs)?.let { NowcastSummary.summarize(it, nowMs) }
            host.log("NOWCAST_SUMMARY", "text=${NowcastSummary.describe(summary, use24HourClock = true) ?: "dry"}", "INFO")
        }
        return outcome
    }
}
