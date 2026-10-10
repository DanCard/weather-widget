# Google `history/hours` refills elapsed hours whose stored forecast went stale

## Symptom (2026-10-10, desktop, Google Weather)

Daily view: yesterday's (Oct 9) forecast high **69.5**. Hourly view for Oct 9: peak **71.7**.
Refresh in the hourly view changed nothing.

## Root cause

- Every `hourly_forecasts` row for Oct 9 was fetched at **Oct 9 04:54**. From 05:20 PT on,
  `forecast/hours` returned 429. The project's daily quota was spent: 84 requests on Oct 9 PT
  (desktop 37 — 24 of them on-demand pages for Oct 15–17 between 03:54 and 04:54 — Pixel 39,
  Fold 8; `scripts/google_usage_today.py --date 2026-10-09`).
- `forecast/days` kept answering (13:58, 22:18), so the daily row moved to 69.5. The hourly rows
  stayed at the 04:54 forecast.
- `forecast/hours` starts at the current hour, so no later refresh can rewrite a past hour.
  Google has no archive of issued forecasts.
- `history/hours` (24 h back) is the only Google product that reaches those hours. Two rules keep
  it from helping today:
  1. `GoogleWeatherApi.needsHistory` asks for it only when the site has **no** history rows in the
     window (a fresh site). The stale 04:54 rows count as coverage.
  2. Elapsed hours never reach the live table (`HourlyForecastStore.saveHourlyEntitiesFromShared`,
     desktop `persistForecastResult`). `ElapsedForecastBackfill` files them into history only where
     there is no snapshot. The stitcher's rule is "live row wins", so even a filed value would not
     be drawn.

## Decision (user, 2026-10-10)

Pull in `history/hours` for yesterday while the 24 h window still reaches it. This deliberately
narrows the "elapsed values never overwrite a forecast" rule, for Google only, to hours whose
stored forecast is **stale**.

Accepted trade-off: those hours then show Google's estimate of what the weather was, not a
forecast. The as-issued record still lives in `hourly_forecast_history` (untouched) and in the
Forecast History view.

## Rule (`:shared`, one place, both platforms)

New `GoogleHistoryRefill` in `shared/.../data/remote/` (beside `GoogleWeatherApi`):

- **Stale elapsed hour:** an hour in `GoogleWeatherApi.historyWindow(now)` whose newest stored
  Google row at the site (live table, same-site match) was fetched more than
  `STALE_LEAD_MS = 6 h` before the hour began, or has no row.
  6 h = the slowest displayed charging cadence (360 min). Routine fetching never leaves an hour
  this stale; a missed day of hourly fetches does.
- **Only a refresh on a previous day (user, 2026-10-10).** The refill runs only when the user
  taps refresh on a screen showing a day before today, and refills only that day's hours (the
  "refill day", `GoogleHistoryRefill.refillDay(viewed, today)`):
  - desktop: the Forecast History window's refresh (its viewed date), and the Observations
    window's refresh (the popup's viewed day when Observations was opened — the popup itself has
    no refresh button);
  - Android: the Forecast History screen's refresh (`ForecastHistoryActivity.refreshViewedSource`,
    its `targetLocalDate`).

  Refresh on today or a future day, Settings → "Refresh data", and every automatic fetch
  (screen-on, wake, network restore, cadence, source toggle, on-demand) never refill. Their
  history rule is unchanged: request only when the window has no coverage at all. A first draft
  used Settings → "Refresh data" as the trigger; the user narrowed it to a past day's refresh.
- **Request history** on a past day's refresh when that day has ≥ 1 stale hour in the window (or
  the window has no coverage at all). Nothing stale → no request.
  - No daily budget is needed. The user chooses when to press refresh, and a refill makes those
    hours fresh (fetchedAt = now), so pressing again finds nothing stale and asks for nothing.
  - The 20/day project quota is still guarded by the existing in-process block after a 429.
  - The window is 24 h, so in practice the refill day is yesterday, and only its hours still
    inside the window (yesterday's afternoon is reachable until early afternoon today).
- **Write:** for the stale hours only, the history values are upserted into the **live**
  `hourly_forecasts` rows (fetchedAt = now). Freshest-wins in `HourlyForecastStitcher` then draws
  them with no stitcher change. Non-stale elapsed hours are untouched. `hourly_forecast_history`
  keeps its existing rule (fill only uncovered hours), so the as-issued snapshots survive.
- History is still dropped when `forecast/hours` itself was refused (existing `parse` rule):
  the refill rides on a fetch that reached Google's hourly product.

## Changes

| Where | Change |
|---|---|
| `shared/.../remote/GoogleHistoryRefill.kt` (new) | `refillDay(viewed, today)`, `staleHours(stored, nowMs, day)`, `shouldRequest(needsHistory, staleHours, refillDay)`, `select`, `STALE_LEAD_MS` |
| `GoogleWeatherApi` | Keep `needsHistory` for the fresh-site half, or fold it into `GoogleHistoryRefill`. `RawFetch` gains `refillHourly` (the stale hours' history values), kept apart from `hourly` so the write paths cannot mistake them for forecast hours |
| Android `ForecastHistoryActivity` → `WeatherRepository.fetchSourceOnDemand(historyRefillDay)` → `ForecastFetchContext.historyRefillDay` | The viewed day, when past |
| Android `ForecastFetchCoordinator.googleNeedsHistory` / `refillStaleGoogleHours` | Read live Google rows in the window, call the shared rule with the refill day, write the refill |
| Android `HourlyForecastStore` | `saveRefillHours(...)`: upsert `refillHourly` into `hourly_forecasts` |
| Desktop `ForecastHistoryWindow` / `ObservationsWindow` (via `DesktopWidgetHeader.onOpenObservations(viewedDate)`) → `DesktopUiApplication.requestFullRefresh/requestSourceRefresh(refillDay)` → `DesktopWeatherRepository.refresh(refillDay)` → `WeatherApiClient.fetchForecast(…, refillDay)` | The viewed day, when past |
| Desktop `DesktopWeatherService.googleNeedsHistory` / `DesktopWeatherRepository.refillStaleGoogleHours` | Same rule and write through `DesktopWeatherDao` |
| Both | Log `GOOGLE_HISTORY_REFILL day=… stale=N refilled=M firstHour=… lastHour=…` on a refill; `stale=N reason=not_requested` (VERBOSE) when any other fetch sees stale hours and leaves them |

## Tests

- `GoogleHistoryRefillTest` (shared, pure):
  - Oct 9 shape: rows fetched 04:54 → hours 11:00–23:00 stale; hours ≤ 10:00 not.
  - Routine 4–6 h cadence → no stale hours.
  - A missing row counts as stale.
  - Stale hours without a refill day → no request; with one → request.
  - Only a day before today is a refill day; a refill day keeps only that day's stale hours.
  - A fresh site (no coverage) still requests on any fetch.
  - A refresh right after a refill → nothing stale → no request.
- Integration (2+ classes), both platforms: a fetch with a stale window and a canned history
  payload rewrites only the stale live rows. `HourlyForecastStitcher` output for yesterday then
  shows the history values; non-stale hours and `hourly_forecast_history` are unchanged.
- `GoogleWeatherApiTest`: `refillHourly` is empty when `forecast/hours` is refused.

## Verification

1. Desktop, before ~13:00 PT so the 24 h window still covers 2026-10-09 13:00–16:00: popup on
   Oct 9 → open Forecast History (or Observations) → refresh.
   - Expect `GOOGLE_REQUEST endpoint=history/hours status=200` and
     `GOOGLE_HISTORY_REFILL day=2026-10-09 …`.
   - Expect the refilled Oct 9 live rows to have `fetchedAt` ≈ now, and the hourly peak near the
     daily row's 69.5 instead of 71.7.
2. Refresh again: no further `history/hours` (nothing stale).
3. Refresh with today in view, or Settings: no `history/hours`; a `reason=not_requested` line.
4. Android: install → Forecast History on yesterday → refresh → the same log pair.
5. `scripts/google_usage_today.py`: `history/hours` +1 per refill, none otherwise.

## Results

- Desktop, 2026-10-10 07:46:58, Observations opened from Oct 9 → refresh:
  `GOOGLE_REQUEST endpoint=history/hours status=200`, then
  `GOOGLE_HISTORY_REFILL day=2026-10-09 stale=13 refilled=13` (11:00–23:00). Oct 9's hourly peak
  went 71.7 → **69.9**, matching that day's own hindcast high (`forecasts.hindcastHighTemp` 69.9).
  User confirmed the graph.
- An earlier refresh the same morning (07:31, before the Observations window carried the viewed
  day) logged `stale=20 reason=not_requested` and refilled nothing, as designed.
- Tests: `GoogleHistoryRefillTest` (9), `DesktopGoogleHistoryRefillTest` (4), two new cases in
  `GoogleWeatherFetchIntegrationTest`; full `:shared` and `:desktop` suites and 90 affected
  Android tests pass.
- Not verified on a phone: the debug build is installed on both devices, but Forecast History is
  not exported, so adb could not open it.

## Not in scope

- The on-demand page burn that spent the quota (3 taps = 24 pages). Worth its own plan:
  continue from stored pages instead of restarting at page 1.
- Other sources. Their payloads already carry elapsed hours, and the existing
  `ElapsedForecastBackfill` rule is unchanged.
