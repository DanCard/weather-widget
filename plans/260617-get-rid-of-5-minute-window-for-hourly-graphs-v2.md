# Remove the 5-minute observation-blend thinning

## Context

The daily-forecast bar and the hourly temperature graph show the same quantity — a day's actual
high/low — but can disagree by tenths (e.g. NWS 2026-06-14: bar 73.7/60.9, graph 73.0/61.0). Both
compute it from `ActualTemperatureSeriesBuilder.blendObservationSeries`, whose greedy **5-minute
dedup-thinning** (`DEDUP_MS`; `if (targetTs - lastEmittedMs < DEDUP_MS) continue`) emits at most one
blended point per 5-minute bucket, advancing from the window start. Because the two views feed it
different windows (daily = `[dayStart,dayEnd]`; hourly = a rolling window), the thinning keeps
*different* representative samples — and can drop the true peak entirely, so neither value is even
correct.

The thinning is a density/performance guard, not a correctness requirement, and the data doesn't need
it: NWS stations report ~every 15 minutes and every other source is hourly, so the set of distinct
observation timestamps is already sparse. Blending once per real report is cheap even on the widest
(30-day-back) zoom. Removing the dedup makes the curve and its extrema faithful to the observations,
and — since both views then blend the *same* observations the *same* way with no window-dependent
thinning — the daily bar and the hourly label agree **by construction**.

(Background: the single-day "pin" that previously forced agreement was removed in a prior change
because its rigid 00:00→24:00 grid fed NaN temps into the renderer; see memory
`hourly_singleday_pin_nan_crash` and `daily_vs_hourly_actual_extrema_mismatch`.)

## Change

One shared function fixes both call sites:

- **`shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt`** —
  in `blendObservationSeries`, remove the `DEDUP_MS` skip so it emits one blended point per distinct
  candidate timestamp (the union of stations' real observation times). Remove the `DEDUP_MS` const.
  Keep `BlendObservationStats.dedupSkippedCount` returning `0` (existing tests assert `== 0`; the
  field documents "no thinning applied") — or drop it if cleaner.

No call-site changes required:
- `ActualsAggregator.blendDailyExtremesViaSeries` (daily bar) takes `max/min` over the blended series
  → now the true daily extreme.
- `ActualTemperatureSeriesBuilder.build` (hourly) → the assembled `hours` carry the un-thinned blend;
  `TemperatureExtrema.compute`'s existing per-day grouping lands on the true max/min for each
  fully-visible day, matching the bar.
- `ActualsAggregator.resolveCurrentObservation` (current temp) also routes through it — unaffected;
  it just blends at every timestamp and still selects the latest.

## Tests

- Extend `shared/src/test/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilderTest`
  test `single-day build reproduces daily aggregate high and low exactly` with **cross-day
  observations** and a sharp off-5-min peak that the old thinning would have dropped, asserting the
  hourly per-day high/low equals `ActualsAggregator.aggregate`'s `daily_extremes` for the target day.
  This is the convergence spec and would have failed under the old thinning.
- `TemperatureViewHandlerActualsTest` blend-stats tests should still pass (sparse obs, so
  `dedupSkippedCount == 0` and the `<= 15` bounds already hold).

## Verification

1. `./gradlew :shared:test testDebugUnitTest`.
2. Build + install on emulator-5554, Pixel (2A191FDH300PPW), Samsung (RFCT71FR9NT)
   (`./gradlew :app:assembleDebug`; `adb -s <serial> install -r -d ...`).
3. On device: tap a past daily bar → hourly view; the pink actual line's labeled high/low must equal
   that day's daily-bar high/low. Repeat for today.
4. `daily_extremes` may shift slightly toward the *true* peak/trough (more correct). Confirm via DB
   (`run-as com.weatherwidget cat …/databases/weather_database`; query `daily_extremes` with local
   `sqlite3`).
5. Desktop sanity (`scripts/buildStart.sh`): daily-bar high/low matches the hourly label; no
   render-time regression on the widest zoom (watch `RENDER_BREAKDOWN` in logs).
6. Avoid `connectedDebugAndroidTest` (removes widgets); use `./scripts/emulator-tests.sh` if needed.
