package com.weatherwidget.shared.util

/**
 * How old the source a user toggles to may be before the toggle refreshes it (user, 2026-10-08):
 * 4 hours, in the daily view and in every hourly view (temperature, cloud, rain) alike. The refresh
 * takes the horizon the cadence says is due, at least SHORT (`ForecastHorizonCadence.forced`).
 *
 * A source with nothing to draw still fetches at once — that arm is the platforms' own
 * (Android `SourceStalenessProbe`, desktop's no-cache launch action).
 *
 * It was 15 minutes, written when every source was free: toggling through sources refetched each
 * one, and with Google billed per request three toggles cost nine `forecast/hours` pages
 * (2026-10-08). 4 hours matches the shortest background cadence for the displayed source, so a
 * toggle refetches only what the schedule should already have.
 *
 * Shared by Android and desktop so the two cannot drift.
 */
object SourceToggleRefreshPolicy {
    const val STALE_MS = 4 * 60 * 60 * 1000L
}
