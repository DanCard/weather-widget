package com.weatherwidget.shared.util

/**
 * Whether a user-chosen location change refetches the forecast of the source on screen (desktop's
 * daemon, the one fetcher for a picker move). Android needs no rule of its own: after adopting the
 * cache it syncs unforced, and each source follows its cadence.
 *
 * The new site's cached graph is shown at once when it has one ([LocationChangePaintPolicy]); the
 * forecast is refetched only when that site has nothing to draw, or when the displayed source's
 * forecast **at that site** is older than [SourceToggleRefreshPolicy.staleMs]. A location change
 * and a source toggle are the same request — "show me this" — so they share one limit (2 h at
 * ≥ 70 % or on a charger, otherwise 4 h).
 *
 * It was every move: on 2026-11-21 switching back to Mountain View refetched NWS twice although its
 * Mountain View forecast was 86 min old (desktop judged a move against 15 min, and the window sent
 * its own unconditional fetch beside the daemon's). Observations are not decided here; each
 * platform's usual observation freshness still applies.
 * plans/261121-location-change-honours-fresh-cache.md
 */
object LocationChangeRefreshPolicy {
    const val TAG = "LOCATION_CHANGE_REFRESH"

    data class Decision(
        val fetchForecast: Boolean,
        val staleAfterMs: Long,
        val forecastAgeMs: Long?,
        val drawableCache: Boolean,
    ) {
        fun logLine(sourceIds: String): String =
            "action=${if (fetchForecast) "fetch" else "keep_cache"} source=$sourceIds drawableCache=$drawableCache " +
                "forecastAgeMs=$forecastAgeMs staleAfterMs=$staleAfterMs"
    }

    fun staleAfterMs(isHighBattery: Boolean): Long = SourceToggleRefreshPolicy.staleMs(isHighBattery)

    /**
     * [lastForecastFetchMs]: the oldest per-site last fetch among the displayed sources (null when any
     * has none at this site). [drawableCache]: the new site's cache is what the screen now shows.
     */
    fun decide(
        drawableCache: Boolean,
        lastForecastFetchMs: Long?,
        nowMs: Long,
        isHighBattery: Boolean,
    ): Decision {
        val staleAfter = staleAfterMs(isHighBattery)
        val age = lastForecastFetchMs?.let { nowMs - it }
        val fetch = !drawableCache || age == null || age >= staleAfter
        return Decision(fetch, staleAfter, age, drawableCache)
    }
}
