# NWS current fetch: recompute today's extremes once, not once per station

Status: **implemented** (2026-10-10).

## Symptom

Pixel 7 Pro, logcat in `backups/20261010_130212_pixel_7_pro_2A191FDH300PPW`, one charging-loop
current-temp fetch:

```
13:02:18.838 … 18.989  OBS_CURRENT_INSERT  KPAO, KNUQ, AW020, LOAC1, KSJC   (5 stations, 150 ms)
13:02:22.329  DAILY_RECOMPUTE_PERF date=2026-10-10 total=3485ms extremes=3166ms contextObs=3226
13:02:22.806  DAILY_RECOMPUTE_PERF date=2026-10-10 total=3861ms extremes=3053ms contextObs=3229
13:02:22.963  DAILY_RECOMPUTE_PERF date=2026-10-10 total=4040ms extremes=3318ms contextObs=3228
13:02:23.320  DAILY_RECOMPUTE_PERF date=2026-10-10 total=4325ms extremes=3732ms contextObs=3229
13:02:23.404  DAILY_RECOMPUTE_PERF date=2026-10-10 total=4489ms extremes=3532ms contextObs=3228
13:02:23.411  NWS_IDW blended=69.2°F from 5 stations totalMs=5037
13:02:23.417  CURR_FETCH_SOURCE_RESULT reason=charging_loop source=NWS durationMs=5751
```

All five recomputes of the same day start within ~150 ms, run concurrently and overlap.

## Root cause

`NwsCurrentObservationUpdater.fetchAndStoreStation` ends every successful station with
`dailyActualsStore.recomputeDailyExtremesForDay(today)`. `fetchNwsCurrent` runs the stations as
parallel `async` jobs, so N stations mean N full recomputes of the same day: each one reads ~3,200
observations, blends them for every source and writes `daily_history`.

The observation-signature skip (`reducedSignatures`) cannot help. Each station's insert changes the
signature, and the signature is recorded only after a run finishes, so all five checks run before
any result is stored and none of them matches.

## Cost

- **CPU:** ~19 s per fetch (5 × 3.5–4.5 s). The charging loop runs every 10 min (16 with the screen
  off), and the battery screen-on loop every ~20 min.
- **Latency:** the current temperature waits for all of it. Network done at ~18.99, result at
  23.41: about **4.4 s of the 5.0 s `totalMs`** is recompute.
- **Correctness (minor):** the runs saw different subsets (3226/3228/3229 obs), and whichever
  persists **last** wins, not whichever saw the most data. Silurian's high flip-flopped
  69.487 ↔ 69.492 between runs and ended on a 3228-obs result. The next fetch fixes it, but the
  stored value can be one station short.

## Fix

Move the recompute out of `fetchAndStoreStation` and into `fetchNwsCurrent`, after
`stationDeferreds.awaitAll`: **one recompute per distinct local date among the successful
stations' timestamps** (normally just today; just after midnight a station's latest reading can
still be yesterday's, which the per-station code also recomputed). `force` stays false, as before.

The order inside `fetchNwsCurrent` stays: stations → recompute → IDW → return. It still runs before
the return, so callers that read `daily_history` after the fetch see the same state as today.
Moving it after the return (fire-and-forget) is possible later, but not part of this change.

Not changed: `NwsObservationBackfiller` also recomputes per station, but sequentially, with
`force = true`, and it stops once the dates are covered. That is a deliberate repair loop, run
rarely.

Desktop already does it once: every `DesktopWeatherRepository` observation path
(`refreshObservationWindow`, the current-reading refresh, `runPostFetchBackfills`) stores the whole
batch and then calls `recomputeDailyExtremes(now)` a single time. This is Android-only.

Expected: ~19 s → ~3 s CPU per fetch, and the current temperature about 3–4 s sooner.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `fetchNwsCurrent` with 5 fake stations all succeeding → exactly **1** recompute of today (counted through a recording `DailyActualsStore` seam or `DAILY_*` rows) | integration (updater + store) |
| 2 | Stations whose latest readings fall on yesterday and today → one recompute per date (2) | integration |
| 3 | All stations fail → no recompute, returns null (unchanged) | integration |
| 4 | The recompute sees every station's row: the `daily_history` high equals the blend over all 5 inserted readings, not a subset | integration (updater + store + Room) |
| 5 | Existing `DailyActualsStore*` tests unchanged and passing | regression |

## Verification on device

After install, on the Pixel while charging: one `DAILY_RECOMPUTE_PERF` (or `DAILY_RECOMPUTE_SKIP`)
per charging-loop NWS fetch instead of five, and `NWS_IDW totalMs` down from ~5 s.

## Verification

- `NwsCurrentFetchRecomputeOnceTest` (new, 4 tests): pass. Run against the old updater, tests 1 and 2
  fail with the bug itself: `expected:<1> but was:<5>` (one fetch, five stations) and
  `expected:<2> but was:<5>` (two dates). Test 4 passes on both versions: the test runs on one
  thread, so the old race never reorders there; it guards the result, not the race.
- `com.weatherwidget.data.repository.*`: 260 tests, 0 failures.
