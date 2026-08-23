# Today column thermostat low: forecast fallback + white label

**Date:** 2026-08-22
**Commit:** (this commit)
**Files:** `DailyActualsEstimator.kt`, `DailyColumnRenderer.kt`,
`DailyForecastGraphRenderer.kt`, `DailyViewLogic.kt` (:app);
`DailyDayValueResolver.kt` (:shared); `DailyForecastGraph.kt` (:desktop)
**Plans:** `plans/260822-today-thermostat-low-forecast-fallback.md` (IMPLEMENTED)

## Problem

Daily forecast view, today column, Open-Meteo (forecast-only source — no
`daily_history` row), observed on the emulator at 16:01:

1. The low label printed **72.3° in red** — the interpolated *current* temperature,
   styled as a settled observed actual. It should have shown the **forecast low
   (57.5°) in white**, since nothing was actually observed.
2. The thermostat (center red bar of today's triple bar) showed only a **dot + bulb**
   — no graphical range down to the day's low.

## Evidence chain

1. Emulator screenshot: red 72.3° bottom label on Today; pink dot instead of a
   thermostat span; all other columns' lows white.
2. logcat `DailyEstimator`: `actual.high=null actual.low=null currentTemp=72.37712
   solidLineLow=72.37712 solidLineLowSource=current_temp dashedLineLow=57.5`.

## Root cause

`DailyActualsEstimator.calculateTodayTripleLineValues` set
`solidLineLow = min(actualLow, currentTemp)`; with no actual row that collapsed to
`currentTemp`. Downstream:

- `DailyDayValueResolver.isLowTrackingActual(isToday, solidLow, nowHour)` treated any
  non-null `solidLow` past the 9am cutoff as a **settled observation** →
  `DailyColumnRenderer` recolored the label `COLOR_OBSERVED_RED`.
- `DailyBarRenderer.drawTodayTripleBar` drew the observed line from `solidLineHigh`
  to `solidLineLow` — both 72.3 → zero-length line, bulb dot only.

## Fix (user-approved rule)

**`solidLineLow` = actual low if it exists; otherwise the forecast low.**

1. **Estimator + shared resolver**: compute the forecast low first, then
   `solidLineLow = actual?.computedLowTemp ?: dashedLineLow`. Mirrored exactly in
   `shared/.../DailyDayValueResolver.resolveTodayLineValues` (desktop parity). New
   `TodayTripleLineValues.hasActualLow` flag records provenance;
   `solidLineLowSource` debug strings now distinguish `daily_actual_low` /
   `forecast_low`.
2. **Color provenance threading**: new `DayData.todayHasActualLow`
   (`DailyForecastGraphRenderer`) set from estimator output in `DailyViewLogic`;
   `DailyColumnRenderer` passes `actualLow` into the shared
   `isLowTrackingActual`, which now requires a genuine observed low before the
   "settled" branch can fire. No actual low → white label showing the forecast low;
   actual low after 9am cutoff → unchanged red.
3. **Thermostat graphic**: no renderer geometry change needed — the bar already spans
   `solidLineHigh → solidLineLow`, so it automatically extends current temp → 57.5°.
4. **Desktop parity**: `DailyForecastGraph.kt` passes `actualLow =
   day.actual?.computedLowTemp` into `isLowTrackingActual` and all three
   `effectiveLowForLabel` call sites.
5. **`isTodayForecastFallback` redefinition** (Android): was
   `solidHigh == null && solidLow == null`; with the new rule `solidLow` is never
   null when a forecast exists, so it became
   `solidHigh == null && !hasActualLow && visible values exist` ("nothing observed
   at all"). Flag is log-only; both prior asserting tests still hold.

## Tests

- Shared `DailyDayValueResolverTest`: new regression tests for
  `resolveTodayLineValues` (no actual → forecast low, not currentTemp; with actual →
  exact actual, no min blend), `isLowTrackingActual` false without an actual even
  after cutoff, `effectiveLowForLabel` settled branch requires `actualLow`;
  existing settled test updated to pass `actualLow`.
- App `DailyActualsEstimatorTest`: no-actual cases now expect the forecast low +
  `hasActualLow=false`; below-actual-low case expects the exact actual low (min-blend
  removed).
- Robolectric (`DailyForecastGraphRendererRoboTest`, user-requested graphical test):
  `today_thermostat_spansDownToForecastLow_whenNoActualLowObserved` asserts the TODAY
  bar's lowY equals the forecast bar's lowY (same 57.5° temp → same Y) and spans
  downward; `today_thermostat_bottomReachesDeeperThanForecastBar_whenActualLowIsColder`
  covers the actual-low case.
- `DailyViewHandlerTest`: snapshot/current-temp test updated (solid/bottom low now
  60° forecast stand-in); fallback tests pass under the redefined flag.

## Verification

- `./gradlew :shared:testShortShared :app:testShortDebugUnitTest` green;
  `:app:testByDurationDebugUnitTest :desktop:testByDurationDesktop` green.
- Installed on emulator-5554; logcat confirms the fix live:
  `solidLineLow=57.5 solidLineLowSource=forecast_low` (was
  `72.37/current_temp`). Note: the widget frame itself was still re-binding
  (loading spinner) at screenshot time after the force-stop/reinstall — the render
  pipeline logs above confirm the daily view resolved with the new values.

## Risks / notes

- Today column layout rescales slightly (minTemp becomes the forecast low instead of
  the current temp) — visually correct: the column now represents the true range.
- Removed min(currentTemp, actualLow) blend per user direction: a midday drop below
  the observed low no longer extends the thermostat below the actual low.
- No schema/DB changes.
