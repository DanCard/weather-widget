# Daily history self-sufficiency: forecast-only rows + forecast/observed color provenance

**Date:** 2026-08-22
**Commit:** uncommitted (working tree)
**Files:** `DailyHistory.kt`, `DailyDayValueResolver.kt`, `DailyHistoryWriter.kt`,
`ForecastOnlyHistoryPlanner.kt` (shared); `DailyHistoryEntity.kt`, `WeatherDatabase.kt`,
`DailyHistoryDao.kt`, `DailyHistorySnapshotter.kt`, `DailyActualsStore.kt`, `DailyViewLogic.kt`,
`DailyBarRenderer.kt`, `DailyColumnRenderer.kt`, `DailyForecastGraphRenderer.kt`,
`ForecastRepository.kt`, `WeatherRepository.kt`, `FullSyncPipeline.kt`, `AccuracyCalculator.kt`
(app); `DesktopWeatherDatabase.kt`, `DesktopWeatherDao.kt`, `DesktopEntities.kt` (shared desktop
persistence); `DesktopDailyForecastModel.kt`, `DesktopWeatherRepository.kt`, `DailyForecastGraph.kt`
(desktop)
**Plans:** `plans/260822-daily-history-forecast-only-rows-kimi-k3.md` (IMPLEMENTED),
`plans/260822-past-day-forecast-label-fallback-kimi-k3.md` (superseded, carried forward)

## Problem

On Samsung (`RFCT71FR9NT`) and the emulator, daily-forecast **history** columns for Open-Meteo lost
their high/low temperature labels after commit `4826fad2` ("Make Open-Meteo forecast-only") deleted
the source's `daily_history` rows and stopped writing new ones. Tomorrow.io showed the same gap for
days before its actuals tracking began (`39b046dc`). Bars and icons still drew, but labels vanished,
and any label recovered from the `forecasts` table would evaporate on its rolling retention.

## Evidence chain

1. Samsung screenshot: past days Wed/Thu/Fri drew bars + icons but no high/low labels.
2. Samsung DB: `daily_history` had NWS/Silurian/Tomorrow.io rows but **zero** `OPEN_METEO` rows;
   Tomorrow.io rows only existed from 2026-08-21 onward. Forecast snapshots for both sources did
   exist for the missing days.
3. Android renderers skip labels when the solid line is null (`DailyBarRenderer.drawHighLabels`
   returns early on `solidLineHigh == null`; `DailyColumnRenderer` low label reads
   `bottomStackLow ?: solidLineLow`), while icons anchor to the forecast fallback — exactly the
   "bar without a number" the screenshot showed.

## Design + fix

### 1. Schema — nullable `computed*`, no fabricated actuals

`daily_history.computedHighTemp/computedLowTemp` became nullable (Room migration 64→65 + desktop
schema v19, both a SQLite table rebuild sharing one DDL constant so they can't drift). NULL computed
is the "no actuals" marker: forecast-only rows never carry model output in an observation column,
so accuracy/scoring math can't be poisoned by self-comparison. `DailyHistory` gained
`displayHighTemp/displayLowTemp`, `hasActuals`, and a nullable `toDailyActual()`.

### 2. Writer — `FORECAST_ONLY_ROW` + `ForecastOnlyHistoryPlanner`

A new shared pure planner decides which (date, source) pairs need a row: a past day with a usable
forecast batch (non-null high+low, non-climate-normal, non-GENERIC_GAP) but no existing row. The
day's latest complete forecast is frozen into the existing `forecastHighTemp/forecastLowTemp`
overlay columns (plus `forecastPrecipAmountMm`), computed stays NULL, `lastWriter=forecast_only_row`.
`DailyHistorySnapshotter.ensureForecastOnlyHistoryRows` (Android) and
`DesktopWeatherRepository.ensureForecastOnlyHistoryRows` run after every sync — idempotent, so one
code path covers both the one-time 31-day backfill and each day's rollover.

### 3. Read side — label from the row, not the forecasts table

Past-day labels are `computed ?: forecast*` **from the row** (`resolvePastLineValues`), so history
renders from `daily_history` alone and survives the `forecasts` table's retention. `DailyActualsStore`
now returns past rows for all active sources while keeping today's live blend capability-gated.

### 4. Accuracy isolation + cleanup guards

`AccuracyCalculator` / `DesktopAccuracyCalculator` skip forecast-only rows as baselines (null
computed). Open-Meteo/Tomorrow.io one-time cleanups now delete only legacy rows
(`AND computedHighTemp IS NOT NULL`), so a forecast-only display row can never be swept.

### 5. Color provenance follow-up (user-reported on emulator)

The first version painted every past day "observed red" (`historyBarPaint`/`pastTempTextPaint`),
even when the solid value was a promoted forecast — Meteo history showed forecast values in red.
Added `solidIsForecastFallback` to `PastLineValues` / Android `DayData` / desktop `DesktopDailyDay`
and threaded it into the renderers: forecast-promoted past days draw forecast-colored bars
(`barForColor`) and white labels, while real-actual past days stay observed red.

## Verification

1. Samsung (`RFCT71FR9NT`): migration applied (computed* nullable, existing rows preserved);
   after the first full sync `FORECAST_ONLY_HISTORY created=61 sources=[OPEN_METEO, TOMORROW_IO, WEATHER_API]`.
2. Emulator: migration applied, installed on all 3 devices.
3. Color is proven deterministically (not by eye): `DailyGapFallbackGraphIntegrationTest` asserts
   the rendered HISTORY bar color — `FORECAST_SUNNY` (amber) for a forecast-promoted past day,
   `OBSERVED` (red) for a real-actuals past day — via `BarDrawnDebug.color`.

## Tests (all green)

- Shared: `DailyDayValueResolverPastLineValuesTest` (promotion + flag), `ForecastOnlyHistoryPlannerTest`.
- Android Robolectric: `ForecastOnlyHistoryRowsTest` (create/idempotent/skip/cleanup-guard),
  `DailyViewLogicTest` (graph+text past-day guards + flag), `AccuracyCalculatorIntegrationTest`
  (forecast-only baseline exclusion), `DailyGapFallbackGraphIntegrationTest` (bar-color policy).
- Desktop: `DesktopForecastOnlyHistoryRowsTest`, `DesktopDailyForecastModelTest` (flag parity).
- Instrumented: `WeatherDatabaseMigrationTest.migrate64To65_nullableComputed...`.

Buckets: `:app:testByDurationDebugUnitTest`, `:shared:testByDurationShared`,
`:desktop:testByDurationDesktop` all green.

Deepseek V4 Pro
