# Share daily-actuals assembly between Android and desktop

## Problem

Android `DailyActualsStore.getDailyActualsWithLiveToday` and desktop
`DesktopWeatherRepository.loadDailyActuals` both assemble "past `daily_history` rows + yesterday from
a previous site + today's live blend". They share the building blocks (`ActualsAggregator`,
`PreviousSiteHistory`, `ActualsProviderResolver`) but compose them separately, and the copies have
drifted:

| Step | Android | Desktop (before) |
|---|---|---|
| Today's low when rows start late (`TodayActualsCoverage`) | nulled (`TODAY_LOW_UNCOVERED`) | **missing** — "lowest since we started watching" drawn as the day's observed low (Samsung 2026-08-22 class) |
| Same-date rows from two fragments inside the box | nearest to the centre | last in `ORDER BY date` |
| Today's persisted `daily_history` row | never read; today is live only | read, then overridden by the live blend when one exists |
| Live blend for past days missing a row | fills (past rows win) | today only |
| Blend input observations | provider apis of the active sources | every api |

## Fix

New pure `:shared` `DailyActualsAssembler.assemble(...)` owns the composition, with Android's
semantics as the canonical ones. Platforms keep only I/O (Room vs JDBC reads) and logging, which
they do from the returned diagnostics (`todayObsSpan`, `liveSummary`, `suppressedTodayLows`).

Inputs are plain shared models: past rows (`DailyHistory`), yesterday donors, observations,
hourly forecasts, active sources, location, today, zone, personal-station weight. Lookback windows
stay with each caller (Android 30 days, desktop 547 for its zoom-out history; desktop's observation
read is wider, which only lets the live blend fill more missing past days).

`ObservationResolver.extremesToDailyActualsBySource` / `mergeDailyActualsBySource` callers outside
this path are untouched.

## Tests

- `:shared` `DailyActualsAssemblerTest`: late-starting today nulls the low only; covered today keeps
  it; past row wins over live for the same day; today's persisted row is ignored; nearest fragment
  wins; a borrowing source (Silurian) gets actuals from METAR rows; previous-site yesterday fill.
- Existing Android `DailyActualsStore*` / `ObservationRepository*` tests must still pass unchanged.
- Desktop: build + restart the app.
