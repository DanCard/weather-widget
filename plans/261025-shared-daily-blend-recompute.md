# Shared daily blend recompute

User, 2026-10-25 (on the desktop window-edge bug found while chasing
`NWS_STATION_ACTUALS_OUTCOME requested=1 pulled=0`): "Shouldn't android and desktop have shared code
for this?" Yes: the planner (`DailyBlendWrite`) is shared, the loop around it is written twice, and
desktop's copy is where the damage comes from.

## Problem

Desktop `DesktopWeatherRepository.recomputeDailyExtremes(now)` blends one `now − 8 d` window and:
1. writes a row for the oldest, partly covered local day (e.g. only 20:00–24:00 of it), and
2. reads existing rows with the window's milliseconds against UTC-midnight keys, so that day's row
   is not found → `DailyBlendWrite` plans an insert → `INSERT OR REPLACE` swaps the whole row,
   bypassing `StoredBlendGuard` and dropping the api actual and frozen forecast columns.

It runs on every observation refresh, so every NWS day is damaged as it ages out (since at least
`5d714276`, 2026-08-08). Desktop vs Pixel, same site (hi / lo, time of low):

| Day | Desktop | Pixel |
|---|---|---|
| 10-03 | 72.9 / 70.6 (23:05) | 91.7 / 59.8 (06:47) |
| 10-14 | 62.6 / 57.3 (23:30) | 77.6 / 48.3 (06:47) |
| 10-17 | 73.4 / 64.5 (22:53) | 84.0 / 55.5 (06:47) |

Every desktop NWS row before 10-19 lacks its api actual; 10-17/10-18 also lost `forecastHighTemp`,
`priorForecastHighTemp` and `noonCloudPercent` at 19:56 (10-18's api actual, written 17:55, was wiped).
Android is unaffected (one whole day at a time, existing row by exact key).

| Step | Android `recomputeDailyExtremesForDay` | Desktop `recomputeDailyExtremes` |
|---|---|---|
| Unit | one local day; callers pass dates | whole window; 5 call sites |
| Unchanged skip | per-day observation signature (`ReducedSignatureStore`) | none |
| Observations | day ± `DAILY_BLEND_CONTEXT_MS` | window, one read |
| Days written | target day only | every touched day, partial oldest included |
| Existing read | exact date key | window ms vs UTC-midnight keys |
| Reach | 9-day cutoff | 8-day window |
| Logs | `DAILY_RECOMPUTE_SKIP`, `DAILY_RECOMPUTE_PERF`, blend breakdown | none |

## Design

`:shared` `DailyBlendRecomputeRun` owns the whole per-day rule, Android's shape:
- `day(host, date, force, nowMs, zone)`: cutoff → signature skip (unless `force`) → observations over
  the day ± context (every api) → empty → done → `ActualsAggregator.aggregateStored` → the target
  day's rows only → existing rows by the exact date key → `DailyBlendWrite.plan` → inserts, conditional
  blend updates, race lines → record the signature → `DAILY_RECOMPUTE_SKIP` / `DAILY_RECOMPUTE_PERF` /
  blend-breakdown logs.
- `range(host, start, endInclusive, force, …)`: `day` for each date.
- `Host`: observations, all-sources hourly, observation signature, existing rows for a key, insert,
  `updateBlendIfUnchanged`, signature store, log, elapsed clock. Android's opt-in
  `EXTREMA_WINDOW_DIAG` probe stays an Android hook (it needs `Log.isLoggable`).
- Android: `DailyActualsStore` per-day body → `day` over `RoomDailyBlendRecomputeHost`.
- Desktop: `recomputeDailyExtremes(now)` → `range(today − N, today)` over `DesktopDailyBlendRecomputeHost`;
  return value (rows written) kept for the 5 callers' logs.
- `DailyBlendRecomputeHostContract` on Room and desktop SQLite (`HostContractCoverageTest`).

## Decisions (user)

- D1 — Unchanged skip on desktop too. Proposed: **yes**, in-memory store (long-lived process); without
  it per-day recompute would redo 8 days on every refresh.
- D2 — Reach. Proposed: **one constant, 9 days** (Android's cutoff) for both; desktop's range becomes
  today − 8 … today (full days only, so no partial day exists to write).
- D4 — Merge rule for colliding fragments. **Decided (user, 2026-10-25): fill gaps** — the most recently
  updated fragment is the base; each column it lacks is filled from the others, freshest first.
- D3 — Repair of desktop history. **Dropped (user, 2026-10-25): "We can skip history repair."** Damaged
  desktop rows stay as they are; the fix stops new damage.

## Phase 0 — daily_history keyed on the rounded site, both platforms

User, 2026-10-25: "Desktop and Android should use the same shared code. Should be rounded for both."
Neither platform rounds `daily_history` keys today; every writer keys on the coordinates it was handed:
- Desktop: every row at the raw config fix (`37.4166014`).
- Android: the raw GPS fix of the moment — the Pixel has rows on 13 keys around this one site
  (`37.417` 164 rows, `37.41683197…` 101, `37.41680908…` 76, …), the Samsung 18.
Reads still work (box + same-site merging), but the key is what `LocationMatch` quantization exists to
fix, and desktop's observations were switched to it on 10-24 while `daily_history` was not.

After rounding, same (date, source, site) collisions: Pixel 5, Samsung 0, desktop 25 (15 with frozen
forecast columns on one fragment only).

- `:shared`: `DailyHistoryKey.quantized(row)` (`LocationMatch.quantize` on both coordinates) and
  `DailyHistoryKey.merge(fragments)` — the one rule both migrations use.
- Write boundary on both: Room `DailyHistoryDao` inserts and desktop `upsertDailyHistory` key every row
  through it, so no writer can mint a raw key again. Updates keep the key of the row they read.
- Migration on both: Room migration (data only, version bump, exported schema) and desktop
  `DesktopWeatherDatabase.initialize`, merging each colliding group by the shared rule. Migration test
  on real SQLite.
- D4 (merge rule) — see Decisions.

## Also (separate, small)

`NwsStationActualsRun` counts today as missing; the planner then drops it (`date.isBefore(today)`), but
only after the station lookup (an uncached NWS `/points` request): 47 such runs on desktop in 3 days,
same on the Pixel. Fix: the range ends at yesterday. Test: only-today-missing makes no `stations()` call.

## Tests

- `DailyBlendRecomputeRunTest` (fake Host): a row at the old window edge carrying api + forecast columns
  survives (fails on desktop's old loop); the target day only is written; signature skip vs `force`;
  cutoff; empty pool writes nothing.
- Desktop integration (real SQLite): recompute twice across the window edge → the oldest day's row keeps
  `apiHighTemp`, `forecastHighTemp`, `priorForecastHighTemp`; its blend is not replaced by an
  evening-only one.
- Android `DailyActualsStore*` tests keep passing (entry points unchanged).

## Verification

1. `./scripts/unit-tests.sh`, `:app:compileDebugAndroidTestKotlin`.
2. Desktop: `scripts/buildStart-desktop.sh`; after the next `OBS_REFRESH`, the oldest day keeps its
   columns and `DAILY_RECOMPUTE_*` lines appear in the desktop log.
3. (D3 dropped: no history repair.)
4. Android: `installDebug`; `DAILY_RECOMPUTE_PERF` as before.

## Progress

- 2026-10-25 — Approved (design, D1 yes, D2 9 days, D4 fill gaps; D3 dropped). Implemented together:
  - Phase 0: `:shared` `DailyHistoryKey` (rounded key + fill-gaps merge). Room `DailyHistoryDao.insertAll`
    and desktop `upsertDailyHistory` round every row; Room `MIGRATION_82_83` and desktop
    `quantizeDailyHistoryKeys` (run from `initialize`) merge old fragments.
  - Phase 1: `:shared` `DailyBlendRecomputeRun` + `ReducedSignatureStore` (moved from `:app`);
    `RoomDailyBlendRecomputeHost` / `DesktopDailyBlendRecomputeHost`; `DailyBlendRecomputeHostContract`
    on both (the signature pinned to one exact string). Desktop `recomputeDailyExtremes` is the run over
    today − 9 … today; its gap-fill recompute is forced per day, as Android's. Desktop signatures live on
    the DAO (repositories are rebuilt on every config/source change).
  - Station actuals: `NwsStationActualsRun` counts past days only.
  - Tests that fail on the old code: `DesktopDailyBlendWindowEdgeTest` (`api actual … was null` at HEAD),
    `NwsStationActualsRunTest.todayAloneMissingAsksNothing` (1 station lookup, expected 0).
    `DailyBlendRecomputeRunTest`, `DailyHistoryKeyTest`, `migrate82To83_…` (emulator, 29/29).
  - Tests adjusted to the rounded key: `DailyActualsStoreCrossSiteTest.rowAt`,
    `NwsStationActualsStoreTest` fragment case; `DesktopApiActualsMergeTest` clears signatures to pin the
    guard rather than the skip.
- Device check after install (backups first: desktop `.backup`, phones `20261025_221313_*`):
  - Desktop: 751 → 718 rows, 0 raw keys, 718/718 groups kept, no group lost a value it had on any fragment.
  - Pixel: v82 → v83, 1326 → 1321 rows, 0 raw keys, no value lost; Samsung: 698 → 698, 0 raw keys.
    (One Pixel copy taken mid-migration had no `-wal` and failed `integrity_check`; the re-pull is `ok`.)
  - Both phones: `DAILY_RECOMPUTE_PERF` / `DAILY_RECOMPUTE_SKIP` from the shared run (persisted signature
    keys still match); no `NWS_STATION_ACTUALS*` lines; no crash.
  - Desktop, first live pass 22:16:46 after restart: 10-16/10-17 updated in place (10-16, now the oldest
    day in reach, kept its frozen forecast); 10-19 … 10-22 untouched (api + forecast intact); 22:19 refresh
    `DAILY_RECOMPUTE_SKIP` for every past day; still 718 rows, 0 raw keys.
