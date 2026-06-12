# Desktop: "History of Forecasts" screen (port of Android `ForecastHistoryActivity`)

## Context

On Android, the hourly graph header shows a rising-line **chart icon** (`ic_forecast_history_line`)
just right of the home icon. Tapping it opens **`ForecastHistoryActivity`** — the "history of
forecasts" screen, which shows *how the forecast for a given day evolved over time* (each snapshot's
predicted high/low plotted against when it was fetched), with an Error-mode toggle, day-by-day
navigation, source cycling, a multi-source accuracy summary, and a data-freshness card.

The desktop app has **no equivalent** — there is no entry icon and no window, so the user cannot
reach this screen at all. (Desktop's existing `StatisticsWindow` / tray "Forecast Accuracy" is a
*different* screen — accuracy metrics, mirroring Android's separate `StatisticsActivity` — and is
not what this task is about.)

**Goal:** Add a faithful desktop port of `ForecastHistoryActivity` plus its entry icon on the hourly
header, **sharing as much code as possible** with Android. The user confirmed: faithful full port,
and refactoring `:app` to share code is acceptable.

## Sharing strategy

`ForecastEvolutionRenderer` (547 lines, `:app/widget/`) is ~70% `android.graphics` Canvas/Paint
drawing, but the geometry/data-prep underneath is pure Kotlin and cleanly separable. This is the same
**shared-engine / platform-draw** split the codebase already uses for `TemperatureLabelEngine` and
`AccuracyPure`. We extract the pure parts to `:shared`, then both Android (Canvas → Bitmap) and
desktop (Compose `DrawScope`) draw from the same geometry.

## Shared module changes (`:shared`)

1. **New `shared/src/main/kotlin/com/weatherwidget/shared/graph/ForecastEvolutionGeometry.kt`** —
   pure logic lifted from `ForecastEvolutionRenderer`:
   - `EvolutionPoint` data class (currently nested in the renderer) — `forecastDate`, `fetchedAt`,
     `daysAhead`, `highTemp`, `lowTemp`, `source: WeatherSource`.
   - `bucketize(points, tempFor)` — 4h-bucket, latest-per-bucket (renderer lines 478–487).
   - `ForecastSample` / `ErrorSample`, error computation (`temp - baseline`).
   - `TimeAxis` math (min/max time, `buildTimeTicks`, `xForTime`, `formatTimeLabel`) — lines 322–336,
     502–517.
   - Label formatters: `formatAxisLabel`, `formatErrorLabel` (lines 519–534). Temp labels keep using
     shared `TempUtils.formatTemp`.
   - Y-axis bound logic for error mode (`yBound`, `computeSymmetric`).
2. **Move `app/.../widget/NiceAxisScale.kt` → `shared/.../shared/graph/NiceAxisScale.kt`.** It is pure
   math (`compute`, `computeSymmetric`, `valueToY`, `ticks`) with no Android deps. Update the import
   in `ForecastEvolutionRenderer.kt` and move/retarget `NiceAxisScaleTest.kt` to `:shared` (or leave
   the test in `:app` importing the new package — confirm Robolectric not required; it is pure).
3. **Extract style constants** from `EvolutionGraphStyle.kt`: move the color strings + dp constants
   (NWS_COLOR, METEO_COLOR, API/APP actual colors, paddings, label gaps, stroke widths) into a shared
   `ForecastEvolutionStyle` object. The Android `Paint`/`PaintSet` factory stays in `:app`; desktop
   reads the same constants for its `DrawScope` colors/strokes.
4. **`DesktopWeatherDao.getForecastEvolution(targetDateEpoch, lat, lon): List<DesktopForecastRow>`**
   — new method mirroring `ForecastDao.getForecastEvolution` (Room query: `WHERE targetDate = ? AND
   <LocationMatch.JDBC_WHERE> ORDER BY forecastDate ASC, batchFetchedAt ASC, fetchedAt ASC`).
   `DesktopForecastRow` already carries exactly the needed columns (targetDate, forecastDate, source,
   highTemp, lowTemp, fetchedAt) — reuse it. Actuals reuse existing
   `getForecastsInRangeBySource(...)` (source-specific) + `getExtremesInRange(...)` (location/app
   actual); no new actual query required because the desktop window always has a concrete source.
5. **Multi-source accuracy summary**: reuse the existing `DesktopAccuracyCalculator.calculateAccuracy`
   per visible source in a small loop (mirrors Android `calculateComparison`); no new shared math —
   `AccuracyPure` already does the work.

## Desktop changes (`:desktop`)

6. **New `desktop/.../ForecastHistoryWindow.kt`** — Compose `Window` modeled on `StatisticsWindow.kt`
   (window-state, `LaunchedEffect` → `Dispatchers.IO` data load) and the Android activity's behavior:
   - Header: title (`Day, Mon D`), prev/next-day buttons (30-day back limit via
     `MAX_HISTORY_DAYS_BACK`), source-cycle button (`config.visibleSources`), mode button
     (Evolution ⇄ Error). Reuse the activity's pure helpers (`shouldShowTemperatureButton`,
     `resolveButtonMode`, `resolveActualLookupMode`, `selectLatestCompleteActualFromForecasts`) — move
     these companion helpers to `:shared` alongside the geometry so desktop calls them directly.
   - Two graphs (high / low) drawn with Compose `Canvas`/`DrawScope`, consuming
     `ForecastEvolutionGeometry` + `NiceAxisScale` + `ForecastEvolutionStyle`. Port the four draw
     routines (`drawGridAndAxes`, `drawSeriesCurve` quad-bezier, `drawErrorSeriesCurve`,
     `drawActualLine`, single-point bar fallback) from Canvas calls to `DrawScope` equivalents
     (`drawLine`, `drawPath`, `drawCircle`, `drawText` via `TextMeasurer`).
   - Legends (NWS/Meteo/actual/app-actual), actuals card, multi-source accuracy summary, freshness
     card (use `config.lastForecastFetchMs` — already present — for "forecast fetched … ago"; battery
     policy text is Android-only, replace with desktop fetch-interval text or omit).
7. **Entry icon** in `Main.kt` hourly header (the emoji row at ~L900–931, beside 🏠): add a chart
   affordance (📈, or load `drawable/ic_forecast_history_line.xml` copied into desktop resources via
   `painterResource`) with `testTag("open_forecast_history")`, `clickable { historyVisible = true }`.
8. **Window plumbing** in `Main.kt`: add `var historyVisible by remember { mutableStateOf(false) }`,
   include it in `anyWindowOpen`, and a conditional render block mirroring the `StatisticsWindow`
   block (~L287): `if (historyVisible && currentConfig != null) ForecastHistoryWindow(weatherDao,
   currentConfig, onClose = { historyVisible = false }, onUpdateConfig = ::saveConfigAndNotify)`.
   Optionally add a tray menu item "Forecast History" next to "Forecast Accuracy".

## Android changes (`:app`) — to enable sharing

9. Refactor `ForecastEvolutionRenderer.kt` to consume `ForecastEvolutionGeometry` /
   `ForecastEvolutionStyle` / shared `NiceAxisScale` (drawing stays Android). `EvolutionGraphStyle`
   keeps only the `Paint` factory, reading shared constants. `ForecastHistoryActivity` keeps
   referencing `ForecastEvolutionRenderer.EvolutionPoint` — re-export a typealias or update the
   import to the shared type. Existing `:app` tests (`ForecastSnapshotDaoTest`,
   `ForecastHistoryButtonRoboTest`, `NiceAxisScaleTest`) must stay green.

## Critical files

- Read/port: `app/.../ui/ForecastHistoryActivity.kt`, `app/.../widget/ForecastEvolutionRenderer.kt`,
  `app/.../widget/NiceAxisScale.kt`, `app/.../widget/EvolutionGraphStyle.kt`,
  `app/.../data/local/ForecastDao.kt` (getForecastEvolution).
- New: `shared/.../shared/graph/ForecastEvolutionGeometry.kt`, `shared/.../shared/graph/NiceAxisScale.kt`,
  `desktop/.../ForecastHistoryWindow.kt`.
- Edit: `shared/.../data/local/desktop/DesktopWeatherDao.kt`, `desktop/.../Main.kt`,
  `desktop/.../StatisticsWindow.kt` (reference template only), desktop drawable resources.

## Verification

- `./gradlew :shared:test` — add a `getForecastEvolution` case to
  `DesktopWeatherDaoTest.kt` (seed multiple snapshots for one targetDate across forecastDates/sources,
  assert chronological order + location-box filtering, mirroring `ForecastSnapshotDaoTest`).
- `./gradlew :app:testDebugUnitTest --tests "*NiceAxisScale*" --tests "*ForecastSnapshot*" --tests
  "*ForecastHistory*"` — confirm the Android refactor didn't regress.
- `./gradlew :desktop:compileKotlin` then `scripts/build-start.sh` (rebuild distributable + restart,
  per CLAUDE.md). In the running app: open the hourly view → click the new 📈 icon → window opens;
  verify Evolution/Error toggle, prev/next-day nav (and 30-day back disable), source cycling, the
  accuracy summary, and the freshness line. Cross-check a past day's graph shape against the same day
  on the Android widget for parity.
