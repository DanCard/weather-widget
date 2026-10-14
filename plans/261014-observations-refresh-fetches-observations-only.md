# Desktop Observations Refresh fetches observations only (plus the past-day Google refill)

## Problem

On 2026-10-14 at 10:09 the user pressed Refresh in the desktop Observations window. It ran the same
full refresh as the popup's Refresh (`REFRESH_CLICK origin=observations` → `REFRESH_ENTER
reason=user_refresh:observations parts=BOTH window=FULL`). For Google that meant 10 billed requests:
`forecast/days`, `currentConditions` and 8 pages of `forecast/hours`. It also rewrote next Tuesday's
daily row, which surprised the user.

User, 2026-10-14: "Refresh on observation screen only refreshes current observations. Refreshes
elsewhere remain the same. Also refresh on prior day, if looking at prior day, per Oct 10 old day fix."

## Cause

`DesktopUiApplication`'s `ObservationsWindow.onRefreshData` calls `requestFullRefresh("observations",
refillDay)`, which calls `DesktopWeatherRepository.refresh(userRefresh = true)`. That is the general
forecast refresh.

## Change

1. **Observations Refresh → observations only.** A new `requestObservationsRefresh(refillDay)` in
   `DesktopUiApplication` runs through `refreshRunner`, so closing the window still can't cancel it.
   It calls `repo.refreshObservations(reason = "user_refresh:observations")`, the same forced,
   recent-only, displayed-source fetch the current-temp loop uses (NWS station pull / borrowed feed /
   provider current; for Google one `currentConditions` request). No forecast, no hourly, no FULL
   window and no `HOURLY_FETCH` marker. It keeps the `REFRESH_CLICK` breadcrumbs, `refreshInFlight`
   suppression, `dataUpdateCount++` and `notifyRefreshRequested()`.
2. **Prior day → also the Oct 10 Google `history/hours` refill**
   (`plans/261010-google-history-refills-stale-elapsed-hours.md`), alone and without the forecast:
   - `:shared` `GoogleWeatherApi.getHistory(lat, lon): RawFetch` makes one billed `history/hours`
     request and returns `providerHistoryHourly` only (`parse(null, null, emptyList(), history)`).
     It honours `historyBlockedUntilMs` and sets it on a 429. It also logs `GOOGLE_REQUEST`.
   - `WeatherApiClient.fetchGoogleHistory(): RawFetch` (default empty), implemented by
     `DesktopWeatherService`.
   - `DesktopWeatherRepository.refillGoogleHistory(day)` does nothing unless the source is Google
     **and** `GoogleHistoryRefill.staleHours` is non-empty, so nothing is billed when nothing is stale.
     It then fetches history and writes it through the existing `refillStaleGoogleHours` rule
     (rows fetched more than 6 h before the hour, live rows only, history snapshots untouched).
   - The Observations Refresh calls it only when `GoogleHistoryRefill.refillDay(viewed, today)` is a
     past day. The viewed date is the popup's, as now.
3. **Unchanged:** the popup / Settings / Forecast History Refresh (still FULL, still refill through the
   forecast fetch), and every background loop. Android's Observations Refresh already fetches current
   conditions and station rows only (`WeatherObservationsActivity.refreshData`), and it has no viewed
   day, so Android does not change.
4. CLAUDE.md: the "Google past-day hourly refill" bullet says the desktop Observations Refresh does
   history only. Add a line about the Observations Refresh scope.

## Tests

| # | Test | Kind | Checks |
|---|---|---|---|
| 1 | `DesktopObservationsRefreshTest`: refresh on today | Integration (repository + DAO + fake client) | the forecast fetch is never called; observations are stored; `OBS_REFRESH` logged, no `REFRESH_ENTER` / `HOURLY_FETCH` |
| 2 | same, Google, past day with stale live hours | Integration | `fetchGoogleHistory` called once; stale rows rewritten; fresh rows and history snapshots untouched; `GOOGLE_HISTORY_REFILL` logged |
| 3 | same, Google, past day, no stale hours | Integration | `fetchGoogleHistory` not called (nothing billed) |
| 4 | same, non-Google source, past day | Integration | no history call |
| 5 | `GoogleWeatherApiTest.getHistory` (Ktor MockEngine) | Unit | one `history/hours` request, no forecast endpoints; elapsed rows parsed; a 429 sets the block and the next call makes no request |
| 6 | `UserRefreshScopeGuardTest` | Guard | also requires `refreshObservations(` / `refillGoogleHistory(` call sites in the UI to sit inside `refreshRunner.start { … }` |
| 7 | Existing `DesktopRefreshObservationsTest`, Google refill tests, desktop suite | Regression | green |

## Live verification

Rebuild and restart the desktop app. Open Observations on today → Refresh. Expected: `REFRESH_CLICK
origin=observations`, `CURR_FETCH_* reason=user_refresh:observations`, `OBS_REFRESH`, and no
`REFRESH_ENTER` or `forecast/*` `GOOGLE_REQUEST`. Open Observations from a prior day → Refresh.
Expected: at most one `history/hours` request and a `GOOGLE_HISTORY_REFILL` line.

## Verification

- Tests 1–6 added and passing: `DesktopRefreshObservationsTest` (today: no forecast overload called,
  observations stored, `CURR_FETCH_START reason=user_refresh:observations`, no `REFRESH_ENTER` /
  `HOURLY_FETCH`); 3 new cases in `DesktopGoogleHistoryRefillTest` (stale → 1 history request, refilled,
  snapshot untouched; nothing stale → no request, `reason=nothing_stale`; NWS → no request); 2 in
  `GoogleWeatherApiTest` (`getHistory` = one `history/hours` request; a 429 blocks until the PT reset);
  `UserRefreshScopeGuardTest` covers the new UI calls. Kept in the existing classes, which already
  hold the fixtures.
- Full suites: `:shared` 2200 tests, 0 failures; `:desktop` 588 tests, 0 failures, 1 skipped. `:app` compiles.
- Desktop rebuilt and restarted 2026-10-14 11:47 (`scripts/buildStart-desktop.sh`). Live click,
  11:55:40 (Observations on today): `REFRESH_CLICK scope=observations refillDay=null` →
  `CURR_FETCH_START reason=user_refresh:observations targets=NWS` → `OBS_REFRESH source=GOOGLE_WEATHER
  obs=33` → `completed` at 11:55:48. No `REFRESH_ENTER`, `REFRESH`, `HOURLY_FETCH` or `GOOGLE_REQUEST`.
  The past-day path has not been clicked live yet; it is covered by the tests.
