package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence

/**
 * How old the source a user toggles to may be before the toggle refreshes it, in the daily view and
 * in every hourly view (temperature, cloud, rain) alike: the viewing limit
 * ([ViewingRefreshPolicy.FORECAST_STALE_WHILE_VIEWING_MS], 2 hours) at ≥ 70 % or on a charger,
 * otherwise 4 hours. The refresh is a full (LONG) fetch, like every other (`ForecastHorizonCadence`).
 *
 * A source with nothing to draw still fetches at once — that arm is the platforms' own
 * (Android `SourceStalenessProbe`, desktop's no-cache launch action).
 *
 * It was 15 minutes, written when every source was free: toggling through sources refetched each
 * one, and with Google billed per request three toggles cost nine `forecast/hours` pages
 * (2026-10-08). A battery below 70 % keeps 4 h, not the cadence's 24 h: the user asked to see this
 * source.
 *
 * Shared by Android and desktop so the two cannot drift.
 */
object SourceToggleRefreshPolicy {
    fun staleMs(isHighBattery: Boolean): Long =
        if (isHighBattery) {
            ViewingRefreshPolicy.FORECAST_STALE_WHILE_VIEWING_MS
        } else {
            ForecastHorizonCadence.intervalMs(isCharging = false, batteryLevel = 100, isScreenOn = true)
        }
}
