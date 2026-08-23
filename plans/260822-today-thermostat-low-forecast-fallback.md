# Today Column Thermostat Low: Forecast Fallback + White Label

**Date:** 2026-08-22
**Status:** AWAITING APPROVAL
**Author:** 0x Alpha Free with feedback from user

## User report

Daily forecast view (emulator, Meteo/Open-Meteo source), today column:

1. The low temperature label is rendered **red**. Since it is a forecast and not an
   actual, it should be **white**.
2. The low temperature is **not graphically shown on the thermostat** (the center red
   observed bar of today's triple bar). When an actual temperature is not available,
   the thermostat should extend down to the **low forecast value**.

## Evidence collected (runtime)

Screenshot (`/tmp/opencode/widget-daily.png`, emulator-5554 @ 16:01):

- Today column bottom label reads **72.3° in red/pink**; every other column's low is
  white (57°, 64°, ...).
- The center "thermostat" (observed) bar shows only a **dot + bulb at 72.3°** — no
  range drawn downward.

logcat (`DailyEstimator`, repeated across renders):

```
today: actual.high=null actual.low=null currentTemp=72.37712
solidLineHigh=72.37712 solidLineHighSource=current_temp
solidLineLow=72.37712 solidLineLowSource=current_temp
fallbackWeather.high=73.0 fallbackWeather.low=57.5
hourlyMax=73.0 hourlyMin=56.4 dashedLineLow=57.5
todayHourlyCount=24 source=OPEN_METEO
```

Open-Meteo never writes a `daily_history` actual row (`actualPresent=false` in
`cloudDecision:` logs), so `currentTemp` stands alone.

## Root cause

Chain for today when **no actual low exists**:

1. `DailyActualsEstimator.calculateTodayTripleLineValues` (app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt:74):
   ```kotlin
   val solidLineLow = listOfNotNull(actual?.computedLowTemp, currentTemp).minOrNull()
   ```
   With `actual == null`, `solidLineLow = currentTemp` (72.3). The current temp is a
   *mercury level*, not an observed overnight low.

2. **Label value** — `DailyDayValueResolver.effectiveLowForLabel(isToday=true,
   solidLow=72.3, forecastLow=57.5, nowHour=16)` (shared/.../DailyDayValueResolver.kt:183):
   `nowHour >= ACTUAL_LOW_CUTOFF_HOUR(9)` and `solidLow != null` → returns the
   *settled* branch value `solidLow = 72.3` instead of the forecast low 57.5.
   The settled branch assumes any non-null `solidLow` came from observations.

3. **Label color** — `DailyColumnRenderer.draw`
   (app/.../widget/DailyColumnRenderer.kt:103–110): `isLowTrackingActual(solidLow=72.3,
   nowHour=16)` → true → `colorOverride = COLOR_OBSERVED_RED`. Red means "settled
   actual", but nothing was observed — forecast-only source. Same conflation exists in
   `isLowTrackingActual` itself (shared resolver).

4. **Thermostat graphic** — `DailyBarRenderer.drawTodayTripleBar`
   (app/.../widget/DailyBarRenderer.kt:408–420) draws the observed line from
   `solidLineHigh` to `solidLineLow`; both are 72.3 → zero-length line, only the bulb
   dot renders.

## Fix plan

**Rule (user-approved):** `solidLineLow = actual low if it exists; otherwise the
forecast low.` No currentTemp fabrication, no null propagation.

### 1. Estimator / shared resolver — resolve solidLineLow from actual-or-forecast

- `DailyActualsEstimator.calculateTodayTripleLineValues`: compute the forecast low
  first (`dashedLineLow = fallbackWeather?.lowTemp ?: hourlyMin`, unchanged), then:
  ```kotlin
  val solidLineLow = actual?.computedLowTemp ?: dashedLineLow
  ```
  - Actual low present → `solidLineLow` equals it exactly (the previous
    `min(actualLow, currentTemp)` drop-below blend is dropped per user direction).
  - No actual row (Open-Meteo etc.) → `solidLineLow` equals the forecast low
    (57.5), never currentTemp.
- Mirror exactly in `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyDayValueResolver.resolveTodayLineValues`
  (**desktop parity** — dual-platform memory rule).
- Update `solidLineLowSource` debug strings to distinguish
  `daily_actual_low` / `forecast_low`.

### 2. Label color — thread "actualness" instead of inferring from solidLow

With (1), `solidLineLow` alone can no longer reveal whether it is an observation or a
forecast stand-in, so:

- Add a flag to `DailyForecastGraphRenderer.DayData` (e.g.
  `todayHasActualLow: Boolean = false`) set in `DailyViewLogic.prepareGraphDayInputs`
  from `tripleValues.solidLineLow != null` provenance (estimator output before the
  merge, or equivalently an explicit boolean added to `TodayTripleLineValues`).
- `DailyColumnRenderer.draw`: gate the red override on
  `day.todayHasActualLow && DailyDayValueResolver.isLowTrackingActual(...)`.
  Extend `isLowTrackingActual` in the shared resolver with the same
  actual-presence guard (defaulted parameter keeps call-site compatibility).
- Result: no actual low → **white** label showing the forecast low; actual low
  present after the 9am cutoff → unchanged red settled behavior.

### 3. Thermostat graphic — falls out of (1)

No geometry change needed in `DailyBarRenderer.drawTodayTripleBar`: it draws the
observed line from `solidLineHigh` (current temp, 72.3) to `solidLineLow`
(now the forecast low, 57.5) with its bulb at the bottom — the thermostat now
graphically shows the low automatically. When an actual low exists the span ends at
the actual low as before.
- Verify the desktop renderer consumes the shared resolver output the same way;
  apply any mirrored change needed there (parity).

### 4. Text view path (narrow widgets)

- `prepareTextDays` today branch: `visibleLow = tripleValues.solidLineLow ?:
  tripleValues.dashedLineLow` → forecast low automatically after (1). Verify no red
  styling exists on text-view lows during implementation.

## Tests

1. **Shared pure unit tests** — update
   `shared/src/test/kotlin/com/weatherwidget/shared/util/DailyDayValueResolverTest.kt`:
   - `resolveTodayLineValues` with `actualLow=null, currentTemp=72.3,
     forecastLow=57.5` → `solidLineLow == 57.5` (was 72.3).
   - `resolveTodayLineValues` with actual low present → `solidLineLow == actualLow`
     exactly (no min-with-current blend).
   - `effectiveLowForLabel` / `isLowTrackingActual`: no actual low after cutoff →
     not tracking actual (forecast stand-in stays white).
2. **Robolectric graphical test** (user requirement — Robolectric or integration test
   for the thermostat graphic), e.g. extend
   `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`
   (existing `TODAY_SNAPSHOT`/bar assertions live here):
   - Render today column via `DailyForecastGraphRenderer.renderGraph` with
     `solidLineLow = 57.5f` (forecast fallback), `solidLineHigh = 72.3f`,
     `nowHour = 16`, Open-Meteo-style data, `todayHasActualLow = false`.
   - Assert the `TODAY` `BarDrawnDebug` lowY equals `layout.tempToY(57.5)` — i.e.
     the thermostat reaches the forecast low (previously it collapsed to highY).
   - Assert the low-label color decision yields white (not OBSERVED_RED) for the
     no-actual case, and stays red when an actual low exists after 9am.
3. Duration buckets per AGENTS.md (`@Category`) for all new/changed tests.

## Verification

1. `./gradlew :shared:testShortShared :app:testShortDebugUnitTest` (+ full duration
   buckets if touched).
2. `./gradlew assembleDebug && ./gradlew installDebug` on emulator-5554.
3. Re-render widget, screenshot, confirm: today low label **white 57°**, thermostat
   line spanning down to the forecast low; other columns unchanged.
4. logcat `DailyEstimator` shows
   `solidLineLow=57.5 solidLineLowSource=forecast_low`.

## Risks / notes

- Layout scale shifts slightly for today (minTemp becomes 57.5 instead of 72.3) —
  bars rescale; visually correct since the column now represents the true range.
- The removed `min(actualLow, currentTemp)` blend means a midday temp drop below the
  observed low no longer extends the thermostat below the actual low — accepted per
  user direction ("solidLineLow should equal actual low if it exists").
- No schema/DB changes.
