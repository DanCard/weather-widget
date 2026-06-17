# Converge Android + desktop hourly single-day view (full day + matching extrema, shared code)

## Context

Clicking a day's bar opens the hourly temperature view. Three related problems:

1. **Android today-click doesn't show the full day.** `navigateToHourlyView`
   (`WeatherWidgetProvider.kt`) only pins the single-day date for **past** days
   (`clickedDate.isBefore(LocalDate.now())`), so clicking **today** falls back to the rolling
   `[now-12h, now+12h]` WIDE window — a partial day. Desktop already shows the full day.

2. **The hourly graph's labeled actual high/low don't match the daily view.** The daily view reads
   `daily_extremes` (written by shared `ActualsAggregator`). Android reproduces those exactly because
   `TemperatureHourDataBuilder.buildHourDataResult` **injects sub-hourly actual points** into the
   `List<HourData>` the label engine consumes, so the off-hour peak/trough is present. **Desktop
   builds `HourData` at hourly resolution only** (`TemperatureGraph.kt`, matching `actualSeries.points`
   by exact top-of-hour `timeMs`), so an off-hour peak (e.g. 1:07pm 72.9°) collapses onto the 1:00pm
   anchor (72.3°). Day-bounded blending alone (already wired on desktop via the now-removed
   `SingleDayWindow`) fixed the low but not the high.

3. **The single-day mechanism is not shared.** Android stores a pinned epoch-day in prefs; desktop
   (my interim change) derived it from the window. Goal: one shared approach.

**Outcome wanted:** clicking any day (incl. today) shows the full midnight→midnight day on both
platforms, the labeled actual high/low equal the daily view exactly on both, and the assembly +
single-day logic live in `:shared`.

## Design

### A. Share the sub-hourly HourData assembly — `:shared`

The builder's output `ActualTemperatureSeriesResult.points` is already the dense series (top-of-hour
forecast anchors **plus** sub-hourly observed points, each carrying `forecastTemp`, `actualTemp`,
`isActual`, `isObservedActual`). Add a shared assembler that maps it to `List<HourData>`, with
platform decoration via a callback so Android keeps its icons/sun/labels and `iconRes:Int` stays in
`HourData` (desktop passes identity):

```kotlin
// shared/.../graph/HourDataAssembler.kt
fun assembleHourData(
    series: ActualTemperatureSeriesResult,
    zoneId: ZoneId,
    decorate: (base: HourData, isTopHour: Boolean, index: Int) -> HourData = { b, _, _ -> b },
): List<HourData>
```

Each base `HourData` = `dateTime`, `temperature = point.forecastTemp`, `actualTemperature =
point.actualTemp.takeIf { point.isActual }`, `isActual`, `isObservedActual`; `isTopHour =
dateTime.minute == 0 && second == 0`.

- **Android** (`TemperatureHourDataBuilder.buildHourDataResult`): replace the hand-rolled phase-1/2
  loop with `assembleHourData(actualSeries, zoneId) { base, isTopHour, _ -> ... }`, where the
  callback reproduces today's decoration (top-hour: `WeatherIconMapper` icon + `SunPositionUtils` +
  `formatHourLabel/Date` + `showLabel`/`isCurrentHour`; sub-hourly: `iconRes=null`,
  `showLabel=false`, sun computed) by looking up `forecastsByTime`/`zoom` from the closure. Rendering
  output must be byte-identical to today (validate with existing Android renderer tests + a
  screenshot).
- **Desktop** (`TemperatureGraph`): build `hourDataList = assembleHourData(actualSeries, zoneId)`
  (identity decorate) and feed it to the label engine instead of the hourly `points.mapIndexed`
  list. This injects the sub-hourly actuals → extrema now match `daily_extremes`. The forecast
  **curve** and actual line keep rendering from their existing separate point lists. **Reconcile
  forecast temps:** desktop currently labels off `forecastTemps`; `series.forecastTemp` uses
  `smoothedForecasts` — pass desktop's smoothed forecasts into `ActualTemperatureSeriesBuilder.build`
  (currently `null`) so the labeled forecast extrema are unchanged.

### B. Full day on click for both, via explicit single-day state — converge on Android's model

Android already bounds the window absolutely when `singleDayDate != null`
(`startHour = singleDayDate.atStartOfDay()`, `endHour = +1 day`), which is **drift-free**. Keep that
as the shared mechanism; the only "weird" part is the today exclusion.

- **Android:** in `navigateToHourlyView` (`WeatherWidgetProvider.kt:584-588`) drop the
  `.isBefore(LocalDate.now())` guard so the clicked date is pinned for **today** (and future) too.
  Keep `WidgetStateManager` single-day prefs and the scroll/zoom/nav clears (`setSingleDayDate(null)`)
  — that is the robust explicit state, not the bug.
- **Desktop:** replace the interim window-derived `SingleDayWindow` with an **explicit** pinned day in
  `DesktopConfig` (e.g. `hourlySingleDayEpoch: Long? = null`). `dayClickConfig` (`Main.kt`) sets it to
  `clickedDate.toEpochDay()`. In `TemperatureGraph`, when set: bound the window to that day absolutely
  `[day 00:00, day+1 00:00]` (overriding the offset/zoom-derived window) and pass `singleDayDate =
  that day` to `ActualTemperatureSeriesBuilder.build`. Clear it (`copy(hourlySingleDayEpoch = null)`)
  in the pan, zoom-scroll, nav-arrow, and toggle-zoom handlers (`Main.kt`) — mirroring Android's
  scroll/zoom clears. Remove `shared/.../actuals/SingleDayWindow.kt` + its test and the interim
  `TemperatureGraph` derivation.
- Optional shared helper for the absolute window both use:
  `SingleDayWindow.dayWindow(date, zoneId): Pair<Long,Long>` (or keep inline — small).

### Why explicit, not window-derived
A drift-free single-day view of **today** must distinguish "pinned to today" from "rolling view that
happens to sit near today's noon"; only stored state does that. Android's `singleDayDate` is exactly
that and already drift-proof; desktop converges onto the same idea.

## Critical files

- `shared/.../graph/HourDataAssembler.kt` (new) — `assembleHourData`; `shared/.../graph/HourData.kt` (unchanged).
- `shared/.../actuals/ActualTemperatureSeriesBuilder.kt` — already has `singleDayDate`; no change beyond use.
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt` — assembly via shared + decorate callback.
- `app/.../widget/WeatherWidgetProvider.kt` — remove today exclusion in `navigateToHourlyView`.
- `desktop/.../TemperatureGraph.kt` — shared assembly; absolute single-day window; pass smoothed forecasts.
- `desktop/.../DesktopConfig.kt`, `desktop/.../Main.kt` — explicit pinned-day field; set on day-click; clear on pan/zoom/nav.
- Remove `shared/.../actuals/SingleDayWindow.kt` (+ test).

## Tests

- `shared` `HourDataAssemblerTest`: a series with an off-hour observed peak yields a `HourData` list
  whose max `actualTemperature` equals the off-hour peak (and equals `ActualsAggregator`'s daily high),
  proving extrema parity for both platforms by construction.
- Keep `ActualTemperatureSeriesBuilderTest` "single-day build reproduces daily aggregate" (green).
- Android: existing `TemperatureHourDataBuilder`/renderer tests must stay green (assembly refactor is
  behavior-preserving). Add/adjust a today-click test asserting `singleDayDate == today`.
- `./gradlew :shared:test` and `:app:testDebugUnitTest` for the touched suites.

## Verification

1. Unit: `./gradlew :shared:test :app:testDebugUnitTest --tests "*HourDataAssembler*" --tests "*TemperatureHourDataBuilder*" --tests "*ActualTemperatureSeriesBuilder*"`.
2. Desktop: `scripts/buildStart.sh`; click **today** → full midnight→midnight day; compare the graph's
   labeled actual high/low against the daily view's numbers for that day — must be equal. Cross-check
   the log `ACTUAL_DAILY` / `LabelAccepted: role=ACTUAL_*` vs `daily_extremes` (epoch-ms `date`).
   Repeat for a past day and a future day.
3. Android: `./gradlew installDebug`; click **today** → full day shown; click a past day → labeled
   actual high/low equal the daily bar; screenshot via the project's PNG→JPG workflow to confirm the
   footer/icons/shading are unchanged by the assembly refactor.
4. Both: after clicking a day, pan/zoom → reverts to the rolling view (single-day cleared); clicking
   again re-pins.
