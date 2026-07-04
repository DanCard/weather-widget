package com.weatherwidget.shared.config

/**
 * Single source of truth for how many days of daily forecast we ask a weather API for, shared by
 * Android (`:app`) and the Linux desktop (`:desktop`) so the two can't drift.
 *
 * Every fetch requests [MAX_DAYS]. This is a *request-formation* constant — the ceiling
 * Open-Meteo's free `forecast` endpoint accepts (`forecast_days` 0..16; 17 is rejected) — not a
 * statement about any source's real coverage. Sources return whatever they can (NWS ~7 days
 * regardless of what's asked; Open-Meteo the full 16), so stored coverage per source is always the
 * deepest that source currently provides, and days past a source's real coverage render as
 * climate-normal filler by design. No per-source capability limits are encoded anywhere: if a
 * provider extends its horizon, the app benefits on the next fetch with no code change; if this
 * constant ever lags a raised Open-Meteo ceiling, we merely under-ask until it's bumped.
 *
 * History: fetches once used a 7-day window, which dropped the day exactly one week out (on a
 * Saturday, *next* Saturday fell off the edge), then an 8-day baseline with on-demand 16-day
 * extension triggers. Always requesting the max subsumed both and let the extension/coverage-gap
 * machinery be deleted (see notes/260703-forecast-coverage-check-deep-dive.md).
 */
object ForecastHorizon {
    /** Open-Meteo's maximum `forecast_days` (today + 15 days); what every fetch requests. */
    const val MAX_DAYS = 16
}
