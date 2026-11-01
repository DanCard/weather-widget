package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence

/**
 * How old the source a user toggles to may be before the toggle refreshes it, in the daily view and
 * in every hourly view (temperature, cloud, rain) alike: the screen-on forecast interval, so 2 hours
 * on a charger and 4 hours off it. The refresh is a full (LONG) fetch, like every other
 * (`ForecastHorizonCadence`).
 *
 * A source with nothing to draw still fetches at once — that arm is the platforms' own
 * (Android `SourceStalenessProbe`, desktop's no-cache launch action).
 *
 * It was 15 minutes, written when every source was free: toggling through sources refetched each
 * one, and with Google billed per request three toggles cost nine `forecast/hours` pages
 * (2026-10-08). Following the cadence means a toggle refetches only what the schedule should already
 * have. A fixed 4 h outlived the 2 h charging interval: on 2026-11-01 a switch to Google at 3.09 h
 * logged `action=NONE`. A battery below 70 % keeps 4 h, not the cadence's 24 h: the user asked to see
 * this source.
 *
 * Shared by Android and desktop so the two cannot drift.
 */
object SourceToggleRefreshPolicy {
    fun staleMs(isCharging: Boolean): Long =
        ForecastHorizonCadence.intervalMs(isCharging = isCharging, batteryLevel = 100, isScreenOn = true)
}
