# Fix: hourly graph stuck on "Loading…" (NaN crash) — remove single-day pin; make extrema window-free

## Context

The hourly temperature graph reproducibly stayed on the **"Today / --° / Loading…"** placeholder on
emulator, Pixel 7 Pro, and Samsung. Device logs (`HOURLY_PAINT_TRACE` instrumentation + logcat)
pinned it to:

```
renderGraph failed: java.lang.IllegalArgumentException: Cannot round NaN value.
  at TemperatureLabelResolver.collectLabelCandidates (roundToInt)
  at TemperatureGraphRenderer.renderGraph
context: resolve widget=52 hourlyForecastsRange=2026-06-16T19:00..2026-06-17T19:00
```

Cause: commit `e0ca3158` extended the **single-day "pin"** to *today*, forcing the hourly view to a
rigid `00:00→24:00` grid. When an update path supplied only a narrow hourly window (`now±12h`), the
trailing evening hours had no forecast → `Float.NaN`; `Float.compareTo` ranks NaN largest, so
`maxByOrNull` picked a NaN index that hit `roundToInt()` → crash → `bitmap=null` → placeholder.

The pin existed only to force the hourly view to use the daily aggregation's exact window+obs so the
two would agree (see memory `daily_vs_hourly_actual_extrema_mismatch`). User's decision: drop the pin
(the rolling/anchor window in `WidgetStateManager.resolveHourlyCenterTime` already gives the intended
behavior — rolls while NOW is visible, fixed anchor otherwise), and fix the *real* reason the daily
bar and hourly label diverge.

## Step 1 — Remove the pin + NaN-safe rendering — DONE & VERIFIED

Implemented, compiles, `:shared:test` + `testDebugUnitTest` green, emulator shows a full render
(`RENDER_BREAKDOWN … labels=1ms`, `state=data`), no crash, rolling `7p→7p` window.

- Removed pin: `WeatherWidgetProvider.navigateToHourlyView` (no `setSingleDayDate`); deleted
  `WidgetStateManager.get/setSingleDayDate` + key (legacy pref cleared in `clearWidgetState`); dropped
  the `singleDayDate` param from `ActualTemperatureSeriesBuilder.build`, `buildHourDataResult`,
  `TemperatureStateResolver`; desktop `hourlySingleDayEpoch` field + day-click pin removed.
- NaN guard (defense-in-depth): `TemperatureExtrema.compute` selects high/low only among finite temps;
  `TemperatureLabelResolver` filters NaN out of `deduplicatedIndices` before the `roundToInt` passes
  (the START/END boundary anchors could otherwise land on a NaN tail).
- Regression test `TemperatureExtremaNaNTest` (shared, plain JUnit) — verified it FAILS without the
  dedup filter, passes with it.
- Diagnostic logging left in for a few days (`HOURLY_PAINT_TRACE`, `resolve_EMPTY/NULL_BITMAP`),
  persisted to `app_logs`; trim later.

## Step 2 — Make extrema window-free: blend per observation (remove 5-min thinning)

Why they diverge today: both views call the same `ActualTemperatureSeriesBuilder.blendObservationSeries`,
but its greedy **5-minute dedup-thinning** (`DEDUP_MS`, skip `if targetTs - lastEmittedMs < DEDUP_MS`)
advances from the window start, so different windows keep different representative samples → different
max/min, and the true peak can be thinned away entirely.

The 5-min thinning is a density/perf guard, not a correctness need. The observation data is naturally
sparse — NWS stations report ~every 15 min, other sources hourly — so blending at **every distinct
observation timestamp** is cheap even on the widest (30-day-back) zoom. Removing the dedup makes the
curve and its extrema faithful, and because both the daily bar and the hourly graph blend the *same*
observations the *same* way with no window-dependent thinning, their high/low **match by
construction** — no per-day-extrema plumbing, no separate code paths.

**Change (single shared function fixes both paths):**
- `shared/.../ActualTemperatureSeriesBuilder.kt` `blendObservationSeries`: remove the `DEDUP_MS` skip
  so it emits one blended point per distinct candidate timestamp. Keep `dedupSkippedCount` in
  `BlendObservationStats` returning 0 (existing tests assert `==0`; data is already >5 min apart) — or
  drop the field if cleaner. `DEDUP_MS` const removed.
- No change needed at the call sites: `ActualsAggregator.blendDailyExtremesViaSeries` (daily bar) and
  `ActualTemperatureSeriesBuilder.build` (hourly) both already route through `blendObservationSeries`;
  `TemperatureExtrema.compute`'s existing per-day grouping then lands on the true max/min for each
  fully-visible day. Current-temp resolution (`ActualsAggregator.resolveCurrentObservation`) also uses
  it — unaffected (just blends at every timestamp; "latest" selection unchanged).

**Tests:**
- Extend `shared/.../ActualTemperatureSeriesBuilderTest` `single-day build reproduces daily aggregate
  high and low exactly` with **cross-day observations** (obs on adjacent days, and a true peak that the
  old 5-min thinning would have dropped) to prove the hourly per-day high/low equals
  `ActualsAggregator`'s `daily_extremes` for the target day. This is the convergence spec.
- Confirm `TemperatureViewHandlerActualsTest` blend-stats tests still pass (they use sparse obs;
  `dedupSkippedCount==0`, bounds hold).

## Verification

1. `./gradlew :shared:test testDebugUnitTest` (NaN regression + extended convergence test).
2. Build + install on emulator-5554, Pixel (2A191FDH300PPW), Samsung (RFCT71FR9NT):
   `./gradlew :app:assembleDebug` then `adb -s <serial> install -r -d ...`.
3. On device: tap a past daily bar → hourly view; the pink actual line's labeled high/low must equal
   that bar's high/low. Repeat for today. No stuck "Loading…".
4. Daily_extremes recompute: values may shift slightly to the *true* peak/trough (more correct);
   confirm via DB (`run-as com.weatherwidget cat …/databases/weather_database`; query
   `daily_extremes` and `app_logs` with local `sqlite3`). Check `HOURLY_PAINT_TRACE` has no
   `resolve_NULL_BITMAP` and paints end in `state=data`.
5. Desktop sanity: `scripts/buildStart.sh`; tap a daily bar → hourly label matches the bar; pan/zoom
   works; no perf regression on the widest zoom (watch `RENDER_BREAKDOWN`).
6. Avoid `connectedDebugAndroidTest` (removes widgets); use `./scripts/emulator-tests.sh` if needed.
