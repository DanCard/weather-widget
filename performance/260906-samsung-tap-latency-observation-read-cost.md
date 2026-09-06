# Samsung tap latency: the observation read is the whole cost

**Date:** 2026-09-06
**Status:** partly done — instrumentation + fixes 1 and 2 landed and measured on device
(uncommitted); fix 3 (the remaining cold-read cost) is an open decision, options at the bottom.

Device: Samsung SM-F936U1 (Z Fold 4), 3 widgets (345, 349, 352), 73 MB database —
`observations` 79,906 rows across **71 distinct device locations**, `hourly_forecast_history`
164,888, `app_logs` 56,407.

## The report

"samsung: very slow on taps."

The app had already recorded the slowness itself. Over three days: `REFRESH_SLOW` 402,
`TOGGLE_API_SLOW` 119, `SET_VIEW_SLOW` 102, `RESIZE_SLOW` 30, `CYCLE_ZOOM_SLOW` 29,
`DAILY_NAV_SLOW` 27, `CLICK_SLOW` 27. Worst single `REFRESH_SLOW`: **68,615 ms**. Typical
interaction repaints ran 7–14 s — an order of magnitude worse than the 1.1–1.4 s that prompted the
2026-07 investigation.

This is **not** a regression of that one. `WidgetInteractionCache` is intact and not implicated; this
is a different root cause that grew in as the observation pool and the location fragmentation did.

## Why the existing instrumentation hid it

The pre-existing log line for the slowest widget read:

```
WIDGET_RENDER_PERF widget=352 view=DAILY useGraph=false resolveMs=4 prepareMs=0 renderMs=0 totalMs=7819
```

`totalMs = now - handlerStartMs`, but on `DailyViewHandler`'s **TEXT branch** `prepareMs` and
`renderMs` are never assigned, and only the header resolve is timed. So 99.9% of the cost sat in code
no timer covered, and the line *reads* like a fast render. `WIDGET_STARTUP_PERF` had the same shape —
its named stages summed to ~1.5 s against `totalMs=10484`.

**Roughly 8 seconds per paint were unaccounted for.** That is what the new logging was for.

## Instrumentation added

| Where | Emits | Gate |
|---|---|---|
| `DailyActualsStore.recomputeDailyExtremesForDay` | `DAILY_RECOMPUTE_PERF` — `query` / `hourly` / `extremes` / `logBreakdown` / `logDiag` / `persist`, plus `contextObs`/`dayObs` counts | `>= 250 ms`, `app_logs` |
| `FullSyncPipeline` | `SYNC_PERF` `backfill=` split into `[nwsObs, metar, synoptic]` and `actuals=` split into `[currentTemps, recompute, repairs, notifier]` | existing `> 500 ms` |
| `ObservationDao.readObservationsInRange` | `OBS_RANGE_READ` — `sql` / `merge` / `diag`, `candidates`, `merged`, `spanH` | `>= 300 ms`, **logcat only** |

`OBS_RANGE_READ` is deliberately `Log.i` and never `app_logs`: this read happens many times per
paint, so its own write would land on the path being measured.

## What the numbers said

One refresh cycle issued **18 reads slower than 300 ms, summing 50.4 s of work**. A single API
toggle — one tap — fires two of them:

```
OBS_RANGE_READ spanH=132 total=4419ms sql=2540ms merge=257ms diag=1622ms candidates=32475 merged=8046
```

Full sync, before any fix:

```
SYNC_PERF total=52162ms weather=3914ms hourly=982ms
  backfill=11118ms [nwsObs=2575ms metar=5ms synoptic=8538ms]
  actuals=27509ms [currentTemps=204ms recompute=26780ms repairs=525ms notifier=0ms]
  widgets=8639ms
DAILY_RECOMPUTE_PERF date=2026-09-04 total=15147ms query=5722ms extremes=4358ms logDiag=4977ms
DAILY_RECOMPUTE_PERF date=2026-09-05 total=8318ms  query=2664ms extremes=2664ms logDiag=2917ms
TEMP_PIPELINE_PERF widget=345 obsQueryMs=5462 buildHourDataMs=964 renderMs=172 totalMs=7975
```

`ObservationDao.readObservationsInRange` is the single dominant cost everywhere it appears — **68% of
the tap-facing paint**. Its three parts, per cycle: `sql` 28.2 s, `diag` 20.0 s, `merge` ~7%.

### Two costs that bought nothing

1. **`ObservationPoolDiagnostics.summarize` ran on every read.** ~1.6 s of every ~4.4 s read, ~20 s
   per cycle. **Exactly one caller consumes it** (`TemperatureStateResolver.kt:630`); the other 23 go
   through `getObservationsInRange`, which takes `.rows` and drops the summary on the floor.

2. **`logExtremaWindowDiagnostic` was ungated.** A pure DEBUG diagnostic that issues its *own* ±24 h
   observation read and runs `blendObservationSeries` **twice** (isolated window and wide window) —
   `logDiag=4977 ms` for a single day, ~7.9 s per sync, ~34% of the whole actuals recompute.

## Why 8.6 s on the Samsung and 94 ms on the emulator

The user's question, answered by experiment: the Samsung database was copied onto emulator-5556 and
the identical build run against it. Candidate/merged counts — what actually drives the cost — come
out near-identical:

| | Samsung | emulator-5556, Samsung data |
|---|---|---|
| candidates → merged | 33,090 → 8,152 | 32,649 → 8,241 |
| **total** | **4,456 ms** | **476 ms** |
| sql / merge / diag | 2547 / 264 / 1645 ms | 295 / 30 / 151 ms |

**~9× of the gap is hardware**, and every sub-stage scales by the same factor (sql 8.6×, merge 8.8×,
diag 10.9×) — a flat platform multiplier, not one pathological operation. The rest is data shape:
emulator-5554's *own* database (10 distinct locations, ~12 k box rows) never crossed the 300 ms
threshold at all. The two multiply. The 9× is not fixable in code, so the only lever is doing less
work.

**Corollary for future sessions: the emulator is a fair reproduction host for this class of bug** —
it carries real history (24 k observations, 72 k hourly) — but it holds 10 observation locations
against the Samsung's 71, so anything driven by coordinate fragmentation shows up scaled down there.

## Changes made

Instrumentation as above, plus:

**1. `ObservationRangeRead.diagnostics` is computed `by lazy`, not on read.**
`readObservationsInRange` now returns `ObservationRangeRead(rows = merged) { summarize(...) }`. An
eager secondary constructor is kept for tests and for any caller already holding a summary.
Observationally inert: the one consumer gets the same numbers, the other 23 stop paying for them.

**2. `logExtremaWindowDiagnostic` is gated on `Log.isLoggable("EXTREMA_WINDOW_DIAG", VERBOSE)`.**
The guard wraps the **work**, not just the write — the existing `AppLogDao` VERBOSE gate only skips
the DB insert, which is the cheapest part of this diagnostic. Same idiom as
`TemperatureGraphAnnotationRenderer`. The diagnostic is deliberately kept, not deleted; enable with:

```bash
adb shell setprop log.tag.EXTREMA_WINDOW_DIAG VERBOSE
```

Files: `data/local/ObservationDao.kt`, `data/repository/DailyActualsStore.kt`,
`widget/FullSyncPipeline.kt`.

### Result, measured on the device

| | before | after |
|---|---|---|
| **tap paint** (`TEMP_PIPELINE_PERF totalMs`, warm) | 7,975 ms | **863 ms** |
| `obsQueryMs`, warm | 5,462 ms | **570 ms** |
| actuals `recompute` | 26,780 ms | **4,610 ms** |
| per-day recompute (09-04 / 09-05) | 15,147 / 8,318 ms | **3,419 / 867 ms** |
| full sync total | 52,162 ms | **28,524 ms** |

Widget verified rendering correctly by screenshot (actuals overlay, today's column, icons, rain %).
Unit tests pass (`*Observation*`, `*DailyActuals*`, `*ActualsRowOrder*`, `*Temperature*Robo*`).

## What remains, and what the evidence says about it

**The remaining cost is disk, not CPU.** At identical row counts, a *cold* read costs `sql=2438 ms`
and a *warm* one `sql=353 ms` — **7×**. The 73 MB database misses the page cache once the process is
evicted, so the first tap after an idle spell still pays ~4.4 s. **The lever is bytes read.**

Two plausible fixes were tested and one is dead:

- **Narrowing the read box does not work.** The SQL box is `LocationMatch.TOLERANCE_DEG` = 0.1° while
  `ObservationSiteMerge.MERGE_TOLERANCE_DEG` = 0.01°, which looks like a 10× over-read. Measured on
  the real data, tightening it removes **3.6%** of rows (49,448 → 47,646): every GPS-jitter fragment
  sits within 0.009° of the others. The 0.1° read exists only for the rare fallback when *nothing* is
  within 0.01°.
- **Dedup is what removes rows: 47,646 → 14,720 (69%).** `ObservationSiteMerge`'s key is
  `(stationId, timestamp, api)` — the `api` is load-bearing, see its KDoc.

Retention is already working (10 days kept). The volume is Synoptic at **330 rows per station-day**
(5-minute sampling; 54,471 of the 80 k rows).

Supporting numbers: the row is **28 columns wide**, and one 132 h result set carries **1.9 MB of text
alone** — 470 KB `rawMetar` + 1.4 MB `stationName`/`condition`. The temperature blend uses ~9 columns
and none of the big text. For scale, the same query on desktop SQLite materializes 49,448 rows in
**50 ms**, so nothing here is a missing index.

### Options

| | What | Est. win | Risk |
|---|---|---|---|
| **A** | **Select fewer columns** on the temperature path — a projection DTO instead of `SELECT *`. Attacks bytes-off-disk directly, which is what the cold-read measurement says matters. | Large | **Low** — no semantic change |
| **B** | **Dedup in SQL** via `ROW_NUMBER() OVER (PARTITION BY stationId, timestamp, api ORDER BY siteDistance, fetchedAt DESC, locationLat, locationLon)`. 69% fewer rows cross the CursorWindow. | Large | **Medium** — must reproduce `ObservationSiteMerge`'s tie-break exactly; Kotlin `Math.round` and SQLite `ROUND` disagree at `.5` on negative longitudes; needs a test paired with `ActualsRowOrderDeterminismTest` |
| **C** | **Downsample Synoptic on write** (330/station-day → hourly + extremes). Shrinks the table itself, so it helps cold reads permanently. | Large | **Medium-high** — irreversible; loses sub-hourly detail the blend may rely on |
| **D** | **Deprioritise actuals against clicks.** Every tap `launchAsync`es on unbounded `Dispatchers.IO`, so 3 widgets stampede and taps queue behind a running sync. A bounded dispatcher + per-widget paint coalescer. Does not reduce work; improves *responsiveness*, which is the actual complaint. | Medium | Low-medium |
| **E** | **Cache the merged series.** Desktop already solved its idle CPU this way — the blend window is 30-min quantized, hence memoizable. Android's `WidgetInteractionCache` is 2 s TTL; a longer TTL keyed on the same window would cover the herd. | Medium | Low |

**Recommendation: A, then D.** A is the biggest win for the least risk and targets disk bytes, which
the cold/warm split identifies as the remaining cost. D addresses responsiveness directly without
touching correctness. B is worth doing after A but needs the determinism test first. Hold C unless a
permanently smaller table is wanted.

## Related

- `plans/` — 2026-07-20 `WidgetInteractionCache` work (different root cause, still valid)
- `ObservationSiteMerge` KDoc — why the dedup key includes `api`
- `arch/daily-history-extremes.md` — the two independent actuals per `daily_history` row
