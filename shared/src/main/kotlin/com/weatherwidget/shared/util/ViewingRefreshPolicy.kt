package com.weatherwidget.shared.util

/**
 * What to refresh while the user is looking at the app (screen on: Android's graph render, desktop's
 * screen-on observation loop). User's rule, 2026-10-08:
 *
 * - **Current temp / actuals** of the viewed source refresh once older than
 *   [ACTUALS_STALE_WHILE_VIEWING_MS]. That is what the 15 minutes was always for.
 * - **The forecast** refreshes only once it is due by the normal background cadence (the interval the
 *   caller passes, from `ForecastCadence`) — never sooner because the screen is on. Screen-on can then
 *   only stand in for a scheduled fetch that was missed (a sleeping laptop), never add one.
 *
 * Until 2026-10-08 the forecast used the 15 minutes too ("cloud-while-viewing"), which fetched the
 * whole forecast — 3 billed Google `forecast/hours` pages — up to four times an hour per client.
 *
 * Shared by Android and desktop so the two cannot drift.
 */
object ViewingRefreshPolicy {
    const val ACTUALS_STALE_WHILE_VIEWING_MS = 15 * 60 * 1000L

    data class Decision(val refreshActuals: Boolean, val refreshForecast: Boolean) {
        val any: Boolean get() = refreshActuals || refreshForecast
    }

    /**
     * @param lastActualsAtMs last current-temp/actuals fetch for the viewed source; null = never.
     * @param lastForecastAtMs last forecast fetch for the viewed source. Null is **not** due: "no
     *   forecast yet" belongs to the missing-data paths, and treating it as due would fetch on every
     *   paint of a source with nothing to show.
     * @param forecastIntervalMs the normal cadence now; null when the battery tier suspends fetching.
     */
    fun decide(
        lastActualsAtMs: Long?,
        lastForecastAtMs: Long?,
        forecastIntervalMs: Long?,
        nowMs: Long,
    ): Decision = Decision(
        refreshActuals = lastActualsAtMs == null || nowMs - lastActualsAtMs > ACTUALS_STALE_WHILE_VIEWING_MS,
        refreshForecast = lastForecastAtMs != null && forecastIntervalMs != null &&
            nowMs - lastForecastAtMs >= forecastIntervalMs,
    )

    /** Forecast half of [decide], for paths whose actuals have their own loop (desktop wake). */
    fun forecastDue(lastForecastAtMs: Long?, forecastIntervalMs: Long?, nowMs: Long): Boolean =
        decide(nowMs, lastForecastAtMs, forecastIntervalMs, nowMs).refreshForecast
}
