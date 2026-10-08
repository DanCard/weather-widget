# Screen on refreshes current temp / actuals, not the forecast

2026-10-08. Follows `plans/261008-google-hourly-quota-hourly-limited-refreshes-and-api-usage-endpoints.md`.

## User's rule

> 15 minute refresh is intended just for current temp / actuals. Should only affect the API that is
> being viewed. Should not be updating the forecast on screen on, unless it passes a stale threshold.

Stale threshold = the normal background cadence (`ForecastCadence` / `ForecastFetchPolicy`): for the
viewed source 240 min screen-on charging, longer on battery. Screen-on never refreshes the forecast
sooner than the background schedule would; it only stands in for a scheduled fetch that was missed.

## Before

| Path | Trigger | Forecast? |
|---|---|---|
| Android cloud-while-viewing (CLOUD graph render) | forecast > 15 min | yes, forced, hourly-limited |
| Desktop cloud-while-viewing (screen-on obs loop) | forecast > 15 min | yes, hourly-limited |
| Desktop wake / network restore (`runLaunchRefresh`) | forecast > 15 min | yes, hourly-limited |
| Current temp / actuals loops | 10–20 min | no |

The 15-minute threshold was the current-temp one, applied to the forecast: on Google, a full forecast
(up to 3 billed `forecast/hours` pages) up to four times an hour per client.

## Change

1. `:shared` `ViewingRefreshPolicy` (replaces `CloudViewingRefreshPolicy`): `decide(lastActuals,
   lastForecast, forecastInterval, now)` → refresh actuals when > 15 min, forecast only when due by
   the cadence interval. No forecast yet is not due (missing-data paths own it).
2. Android watchdog (`CloudCoverViewHandler.maybeRefreshWhileViewing`, was
   `maybeRefreshCloudWhileViewing`): actuals stale → current-temp refresh for the viewed source only
   (`viewing_actuals`); forecast due by cadence → targeted forced refresh, hourly-limited
   (`viewing_forecast_due`). Debounced per widget + source as before.
3. Desktop screen-on obs loop: no forecast refresh unless due by cadence (`viewing_forecast_due`); the
   loop's observation fetch already refreshes the viewed source's current temp and actuals.
4. Desktop wake / network restore: forecast only when due by cadence; otherwise the existing
   observations-only catch-up (10-min freshness).
5. Trigger renamed `cloud_while_viewing` → `viewing_actuals` / `viewing_forecast_due`.
6. Unchanged: source toggle (daily view hourly-limited, hourly view full, when stale), refresh
   button, startup, location change, the cadence itself. Desktop startup / source switch keep the
   15-min user-present threshold (`FORECAST_FRESHNESS_THRESHOLD_MS`, now a literal in `DesktopProcess`).

## Tests

- `ViewingRefreshPolicyTest`: 30-min-old forecast → actuals only; past cadence → forecast; thresholds;
  null forecast and suspended cadence not due.
- `ViewingRefreshRoboTest` (Android watchdog): forecast fresh → no forced refresh; due → targeted
  hourly-limited forced refresh; null repository / other-source rows quiet; cooldown marked.
- `DesktopApiUsageAndRefreshGateTest`: wake forecast gate.
