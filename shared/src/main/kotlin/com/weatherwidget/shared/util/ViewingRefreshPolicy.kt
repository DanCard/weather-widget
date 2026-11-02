package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence

/**
 * What to refresh while the user is looking at the app (screen on: Android's graph render, desktop's
 * screen-on observation loop). User's rule, 2026-10-08:
 *
 * - **Current temp / actuals** of the viewed source refresh once older than
 *   [ACTUALS_STALE_WHILE_VIEWING_MS]. That is what the 15 minutes was always for.
 * - **The forecast** of the viewed source refreshes once older than
 *   [FORECAST_STALE_WHILE_VIEWING_MS] at ≥ 70 % or on a charger (user, 2026-11-01, replacing the
 *   same-day "every source every 2 h on a charger"); otherwise once due by the normal background
 *   cadence. [ViewingForecastRefresh] applies it, for the viewed source only.
 *
 * Until 2026-10-08 the forecast used the 15 minutes too ("cloud-while-viewing"), which fetched the
 * whole forecast — 3 billed Google `forecast/hours` pages — up to four times an hour per client.
 *
 * Shared by Android and desktop so the two cannot drift.
 */
object ViewingRefreshPolicy {
    const val ACTUALS_STALE_WHILE_VIEWING_MS = 15 * 60 * 1000L

    /** How old the viewed source's forecast may be at ≥ 70 % or on a charger. */
    const val FORECAST_STALE_WHILE_VIEWING_MS = 2 * 60 * 60 * 1000L

    /** The wait after a viewing fetch request: a failure records no fetch, so it would otherwise retry on every look. */
    const val FORECAST_RETRY_COOLDOWN_MS = 15 * 60 * 1000L

    data class Decision(val refreshActuals: Boolean, val refreshForecast: Boolean) {
        val any: Boolean get() = refreshActuals || refreshForecast
    }

    /** How old the viewed source's forecast may be right now: the viewing limit, else the normal cadence. */
    fun forecastIntervalMs(isCharging: Boolean, batteryLevel: Int, isScreenOn: Boolean): Long =
        if (isScreenOn && ForecastHorizonCadence.isHighBattery(isCharging, batteryLevel)) {
            FORECAST_STALE_WHILE_VIEWING_MS
        } else {
            ForecastHorizonCadence.intervalMs(isCharging, batteryLevel, isScreenOn)
        }

    /**
     * @param lastActualsAtMs last current-temp/actuals fetch for the viewed source; null = never.
     * @param lastForecastAtMs last forecast fetch for the viewed source. Null is **not** due: "no
     *   forecast yet" belongs to the missing-data paths, and treating it as due would fetch on every
     *   paint of a source with nothing to show.
     * @param forecastIntervalMs [forecastIntervalMs] now; null when nothing is scheduled.
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
