# Tomorrow.io "legacy" cleanup deletes the daily history it just computed

## Symptom
Fold, 2026-09-28: after moving to Warsaw at 09:14, Tomorrow.io's yesterday (09-27) had no row until
09:30, though its 166 five-minute readings for the day were stored from 09:14:40.

## Evidence
- 09:14:46 `DAILY_RECOMPUTE_PERF date=2026-09-27 contextObs=746 dayObs=407` — replaying
  `ActualsAggregator` offline on the 09:16 backup with the same 746 rows yields
  `TOMORROW_IO=68.14/54.45` (and Silurian 67.98/53.55), so the row was computed and inserted.
- 09:14:47 second `TMRW_5M_FETCH … replacements=276`, then
  09:14:48 `TMRW_5M_CLEANUP lat=52.233… coverage=present retiredObservations=0 dailyRows=1`.
- 09:14:49 `DAILY_RECOMPUTE_SKIP date=2026-09-27` — observations unchanged, so nothing rebuilt it.
- 09:30:08 row reappears only because the Synoptic fetch changed the day's signature.
- 11 such deletions on the fold since 2026-09-26 (Kyiv, Lviv, Warsaw), every one `retiredObservations=0`.

## Root cause
`TomorrowIoLegacyActualsCleanup.retireConflictingProductsIfCovered` (added 80c977c3, 2026-09-10) runs
after **every** Tomorrow.io fetch (`ForecastRepository` and `CurrentTempRepository`). Whenever the
site has five-minute coverage it runs `deleteTomorrowIoHistoryAtSite`, which deletes every
computed TOMORROW_IO `daily_history` row at the site — including rows the recompute built from the
five-minute product itself moments earlier. The recompute's signature skip then treats the day as
settled, so the row stays gone until that day's observations change.

## Fix
Delete daily rows only when the same call actually retired legacy observations
(`observationsDeleted > 0`): those are the only rows that could have been built from the retired
products, and deleting the observations changes the day's signature so the recompute rebuilds it.
Once a site's legacy rows are gone the cleanup becomes a no-op, which was its intent.

## Tests
- Robolectric: 5-min coverage + a computed TOMORROW_IO row + no legacy observations → row survives.
- Legacy observations present → they and the daily row are removed (existing behaviour kept).

## Verification
Fold: after a Tomorrow.io fetch, `TMRW_5M_CLEANUP` no longer logs `dailyRows=1` with
`retiredObservations=0`, and Tomorrow.io's yesterday row persists.

## As implemented (user: shared Android/desktop code, generic for all APIs, delete only old data)
- `RetiredActualsProducts` (`:shared` `data.local`): registry of product migrations. An entry
  names a source and its **current** product; retired rows are the source's rows outside it, so
  the cleanup can only ever remove data no current code writes. Tomorrow.io is the only entry.
- `RetiredProductCleanup` (`:shared` `actuals`): the one decision — nothing until the replacement
  has rows at the site; daily rows only in the same run that retired observations.
- Android `RetiredProductCleanupRunner`: `@RawQuery` selects row ids from the registry's WHERE
  clauses, deletes go through Room-checked `rowid IN (…)` queries (invalidation tracking intact).
  Runs for every source a forecast sync fetched, and after Tomorrow.io's five-minute fetch.
- Desktop `DesktopWeatherDao.retireProductsIfCovered`: same engine in one JDBC transaction per
  product; runs for every source whose readings `persistObservations` stored.
- Removed: `TomorrowIoLegacyActualsCleanup`, `OpenMeteoLegacyActualsCleanup` (dead: never called),
  desktop `cleanupLegacyTomorrowIoActuals` / `cleanupLegacyOpenMeteoActuals`, and their DAO methods.
  Log tag is now `RETIRED_PRODUCT_CLEANUP api=…`.
- Tests: shared engine + registry; Android runner on Room (retire after coverage and per site,
  keep current-product rows, keep forecast-only rows, unregistered source untouched); desktop DAO.
  Mutation check: dropping the "only alongside retired rows" guard fails one test in each module.
- Fold verification: 09:56 sync fetched Tomorrow.io, the cleanup deleted nothing, Warsaw's
  2026-09-27 Tomorrow.io row (68.14/54.45) survived. The last deletion (09:36:41) was the old build.
