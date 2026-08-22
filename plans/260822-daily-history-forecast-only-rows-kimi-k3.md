# daily_history: forecast-only rows so the table alone renders daily history

Date: 2026-08-22
Status: IMPLEMENTED (uncommitted) — design approved 2026-08-22, implementation complete.
Supersedes the end-state of `260822-past-day-forecast-label-fallback.md` (display-only fallback);
its root cause/evidence and its read-side resolver are carried forward here.

## Implementation status

Implemented and verified end-to-end:

1. `shared`: `DailyHistory.computedHighTemp/LowTemp` → nullable, plus `displayHighTemp`/
   `displayLowTemp`/`hasActuals` and `toDailyActual()?`; `DailyHistoryWriter.FORECAST_ONLY_ROW`;
   `ForecastOnlyHistoryPlanner` (pure rules); `DailyDayValueResolver.resolvePastLineValues`.
2. `Android`: Room migration 64→65 (nullable `computed*`, table rebuild via the shared DDL
   constant); entity/DAO ripple; `DailyHistorySnapshotter.ensureForecastOnlyHistoryRows` wired
   through `ForecastRepository`/`WeatherRepository`/`FullSyncPipeline`; cleanup guards
   (`... AND computedHighTemp IS NOT NULL`) on both Open-Meteo and Tomorrow.io deletes;
   `DailyActualsStore.getDailyActualsWithLiveToday` now returns past rows for all active sources
   (today's live blend stays capability-gated); accuracy baselines skip forecast-only rows.
3. `Desktop`: schema v19 (same DDL rebuild); `DesktopWeatherRepository.ensureForecastOnlyHistoryRows`
   after every fetch; `DesktopForecastRow` extended; accuracy baseline guard.
4. Tests (all green): shared resolver + planner; Robolectric `ForecastOnlyHistoryRowsTest`,
   `DailyViewLogicTest` (graph+text past-day guards), `AccuracyCalculatorIntegrationTest`
   forecast-only isolation, `DailyGapFallbackGraphIntegrationTest` policy update; desktop
   `DesktopForecastOnlyHistoryRowsTest` + `DesktopDailyForecastModelTest`; instrumented
   `WeatherDatabaseMigrationTest.migrate64To65_...`.
5. On-device (Samsung `RFCT71FR9NT`): migration applied (computed* nullable, data preserved),
   `FORECAST_ONLY_HISTORY created=61 sources=[OPEN_METEO, TOMORROW_IO, WEATHER_API]` after the
   first full sync.

## Follow-up found during verification (NOT yet fixed — awaiting approval)

Daily-history labels for forecast-only days render in the **observed-red** color instead of the
forecast-white color. Root cause, evidence, and plan below in "Color provenance follow-up".

## Goal

`daily_history` must be **self-sufficient to render the daily history view**: every past day,
for every display source, has a row carrying everything the column needs (high/low to label,
frozen forecast overlay, precip, noon cloud) — independent of the rolling retention of the
`forecasts`/`hourly_forecasts` tables.

## Current state (evidence-backed)

1. `daily_history` (Room v64, `DailyHistoryEntity`) already has the archival forecast columns:
   `forecastHighTemp`, `forecastLowTemp`, `forecastPrecipAmountMm`, `noonCloudPercent`
   (frozen overlay, `DailyHistoryFreeze`, monotone window-gated merges).
2. Blocker: `computedHighTemp` / `computedLowTemp` are `NOT NULL`, so a row cannot carry only
   forecast values. No writer creates rows for sources without actuals.
3. Provenance scaffolding already on the row: `actualsSource` (`DailyActualsSource`) and
   `lastWriter` (`DailyHistoryWriter`, diagnostic).
4. Samsung evidence: no OPEN_METEO rows (deleted by `OpenMeteoLegacyActualsCleanup`,
   commit `4826fad2`); no TOMORROW_IO rows before 2026-08-21 (tracking started in `39b046dc`);
   forecast snapshots exist for those days and get purged on retention.
5. Consequence today: past-day columns lose high/low labels when the row is absent (Android
   renderers skip labels without solid values; icons/bars still draw — Samsung screenshots),
   and any labels recovered via forecasts-table fallback evaporate on retention purge.

## Design

### 1. Schema — one nullable migration, NO new temperature columns

1. Room migration 64→65: `computedHighTemp`, `computedLowTemp` become nullable.
   Desktop parity: same change on the desktop `daily_history` mirror (`DesktopWeatherDao`).
2. **No fake numbers in `computed*`.** They stay null for forecast-only sources. The null IS
   the "no actuals for this source" marker; accuracy consumers skip nulls naturally. Writing
   `computed = forecast` was explicitly rejected: `AccuracyCalculator` treats `computed*` as
   ground truth, so self-comparison would report fake 0° error and undo commit `4826fad2`'s
   provenance work.
3. Nullable `computed*` makes Kotlin force every reader to state a policy — the consumer audit
   is compile-error-driven instead of convention-driven.

### 2. Writers

1. New `DailyHistoryWriter.FORECAST_ONLY_ROW` ("forecast_only_row").
2. New shared writer: at day rollover / during the freeze pass, for a source with
   `WeatherSource.supportsTemperatureActuals == false` whose row is missing, create the
   `daily_history` row freezing that day's most recent complete forecast batch into
   `forecastHighTemp/LowTemp` (+ `forecastPrecipAmountMm`, `noonCloudPercent` via the existing
   `DailyHistoryFreeze.merge` rules — monotone, window-gated, high/low move as a unit).
   Writer is generic over capability, so tomorrow.io pre-tracking days and any future
   forecast-only source are covered by the same code.
3. One-time idempotent **backfill**: walk recent past days; for source/date pairs with no row,
   create forecast-only rows from the latest complete snapshot still in `forecasts`.
   Days older than forecast retention cannot be recovered — accepted.
4. Reconcile `OpenMeteoLegacyActualsCleanup`: scope its delete to legacy rows (non-null
   `computed*`) so it cannot delete the new computed-null rows. Add a guard test.

### 3. Read side

1. Past-day label resolution: `computed ?: forecast*` **from the row itself**. This absorbs the
   transitional display fallback (`DailyDayValueResolver.resolvePastLineValues`, already
   wired into Android graph/text paths and desktop `buildDay`) and re-sources it from the row:
   labels then survive forecasts-table retention, which the transitional fallback cannot.
2. `resolvePastDayOverlay` already prefers the frozen columns; with rows always present the
   previews-table snapshot fallback becomes truly last-resort.
3. Consumer audit for the nullable `computed*` change (compile-driven): Android
   `DailyViewLogic`, yesterday-header/yesterday-delta, `StatisticsActivity`,
   `ForecastHistoryActivity`, `AccuracyCalculator`/`RainAccuracyCalculator`,
   `AccuracyBaselineField`/`ActualsBaselineResolver`, `DailyHistorySnapshotter`,
   overlay resolvers; desktop `DesktopDailyForecastModel`, DAO mappers
   (`getFloat` → nullable read), stats windows.

### 4. What does NOT change

1. NWS/Silurian/post-tracking-Tomorrow.io rows: writers, values, and rendering unchanged.
2. No Open-Meteo actuals are re-introduced; `4826fad2` provenance stands. Accuracy math never
   sees forecast values.
3. `DailyHistoryFreeze` window/merge semantics unchanged; the new writer reuses them.

## Test plan (regression prevention — follows the framework ladder)

### Shared (pure JVM, `:shared:testShortShared`)

1. `resolvePastLineValues` cases (already listed in the superseded plan): actual present →
   passthrough; no actual + forecast → fallback; no actual + no forecast → null; partial
   forecast stays partial; forecast never promoted when an actual exists.
2. New-writer unit tests: freeze-window gating, monotone merge (null→value, value→newer, never
   value→null), high/low unit move, idempotency (second run is a no-op), capability filter
   (no row written when a real-actuals writer owns the source).

### Android Robolectric (`:app:testShortDebugUnitTest` + affected buckets)

3. **Migration 64→65**: legacy row with computed values survives; new forecast-only row with
   null computed inserts/reads (Room real-SQLite migration test).
4. **Backfill**: seeds `forecasts` with OPEN_METEO past-day snapshots and no `daily_history`
   row (the exact post-`4826fad2` Samsung state) → rows created with frozen forecast values;
   second run creates nothing; NWS rows untouched.
5. **Cleanup guard**: `OpenMeteoLegacyActualsCleanup` deletes legacy computed-non-null rows but
   preserves computed-null forecast-only rows.
6. **Read-side regression guard** (`DailyViewLogicTest`, graph + text): past day with a
   forecast-only row → labels from the row's forecast values; with a real-actuals row →
   unchanged. Covers the original Samsung symptom end-to-end at logic level.
7. **Accuracy isolation**: `AccuracyCalculator`/`RainAccuracyCalculator` skip null-computed
   rows (no self-comparison inflation).
8. **Integration (≥2 real components)**: seed in-memory Room (forecasts + backfill run), then
   run the real loader → `prepareGraphDays`; assert past-day solidLine values non-null. Locks
   writer and widget read path together.

### Desktop (`:desktop:testShortDesktop`)

9. Mirror: writer/backfill on the desktop mirror DB, `DesktopDailyForecastModel.buildDay` past
   day with forecast-only row → solid labels from frozen forecast; nullable DAO mapper read.

### Keep-green / watch-list

10. Existing past-day bait tests ("must NOT synthesize forecastHigh from climate normals") must
    stay green — the fallback never routes through climate normals.
11. `YesterdayActualHighConsistencyTest`, `NwsHistoryIntegrationTest`,
    `OpenMeteoLegacyActualsCleanupTest` (extended, not relaxed).

### On-device verification (Samsung `RFCT71FR9NT`)

12. `installDebug` → Meteo source: Wed/Thu/Fri columns show high/low labels (screenshot).
13. Tomorrow.io: 8/19–8/20 labeled (backfilled forecast-only rows); 8/21 shows actual.
14. NWS: unchanged (actuals remain the label).
15. Retention robustness: after backfill, labels persist for days whose `forecasts` rows are
    gone (deep history) — spot-check one such day if data allows.
16. Desktop `:desktop:run` on an Open-Meteo location: history labeled.

## Risks / decisions

1. Room migration risk is modest (two columns to nullable; SQLite column type stays `REAL`,
   only the NOT NULL constraint drops — verify the generated SQL uses a table rebuild that
   preserves indices and PK `(date, source, locationLat, locationLon)`).
2. The nullable-`computed*` ripple is the bulk of the diff and is intentional: every forced
   `Float?` handling site is a formerly-implicit leak made explicit.
3. Backfill cannot resurrect forecast values older than `forecasts` retention — accepted;
   from install day forward, the freeze writer keeps rows self-sufficient going back.

## Work breakdown (implementation order)

1. Shared: model nullability + new writer + `DailyHistoryWriter.FORECAST_ONLY_ROW` + tests.
2. Android: migration 64→65, DAO/entity ripple, backfill, cleanup guard, read-side re-source;
   Robolectric + migration tests.
3. Desktop: DAO mirror schema + writer + model read-side; tests.
4. Buckets green; assembleDebug + installDebug; Samsung verification; update session log.

## Color provenance follow-up

### Evidence

1. Emulator screenshot (Meteo daily view): past days Thu/Fri show high/low labels `71.9°/58.9°`,
   `72.8°/58.9°` in the pink/observed-red color, with matching red bars; future days show white
   labels. The convention is red = observed actual, white = forecast — but for Meteo these
   history values are *forecasts* (no actuals exist), so they must be white.
2. Paints confirm the leak: `pastTempTextPaint` = `COLOR_OBSERVED_RED` and `historyBarPaint` =
   `COLOR_OBSERVED_RED`, applied to every past day unconditionally (`DailyBarRenderer.drawHighLabels`
   picks `paints.pastTempTextPaint` when `day.isPast`; `drawDayBars` picks `historyBarPaint` when
   `day.isPast`; `DailyColumnRenderer` low label uses `pastTempTextPaint` when `day.isPast`).
3. Same on desktop: `highColor = COLOR_OBSERVED` when `day.isPast`; `lowColor = COLOR_OBSERVED`
   when `day.isPast || todayLowSettled`; `drawRangeLine(day.solidHigh..solidLow, COLOR_OBSERVED)`.

### Root cause

`resolvePastLineValues` promotes the forecast into the solid line when a past day has no actuals,
but nothing records *why* the solid value is set. The renderers key purely off `isPast` to choose
"observed" styling, so a promoted forecast is painted red like a real observation. The bar and
both temperature labels need a provenance flag.

### Proposed fix (review before implementing)

1. Add `solidIsForecastFallback: Boolean` to `DailyDayValueResolver.PastLineValues` (true when
   the actual was absent and the solid values came from the forecast) and to the Android
   `DayData` and desktop `DesktopDailyDay`.
2. Android renderers (`DailyBarRenderer`, `DailyColumnRenderer`): when the flag is set, style the
   past day's solid bar and high/low labels with the **forecast** paints (white `tempTextPaint`,
   `COLOR_FORECAST` bar) instead of the observed-red ones, and suppress the observed-red overrides.
3. Desktop `DailyForecastGraph`: mirror — `COLOR_FORECAST`/white when the flag is set instead of
   `COLOR_OBSERVED`.
4. Tests: shared `resolvePastLineValues` flag assertions; `DailyViewLogicTest` asserts the flag is
   true for forecast-only rows and false for real-actual rows; desktop model test likewise.

No changes to the writer/migration/accuracy work — this is renderer-only provenance styling.

## Color provenance follow-up — DONE

Implemented the approved plan:

1. `DailyDayValueResolver.PastLineValues.solidIsForecastFallback` (true when actual absent and
   solid == forecast); threaded into Android `DayData.solidIsForecastFallback` and desktop
   `DesktopDailyDay.solidIsForecastFallback`.
2. Android `DailyBarRenderer`: forecast-promoted past days draw their solid bar with the forecast
   condition color (`barForColor`) and their high/low labels with `tempTextPaint` (white) instead
   of the observed-red `historyBarPaint`/`pastTempTextPaint`.
3. Android `DailyColumnRenderer`: low label uses `tempTextPaint` for promoted past days.
4. Desktop `DailyForecastGraph`: solid bar `forecastColor`, high/low labels white when promoted.
5. Tests (all green):
   - `DailyDayValueResolverPastLineValuesTest`: flag true on promotion, false otherwise.
   - `DailyViewLogicTest`: flag true for forecast-only rows, false for real actuals.
   - `DailyGapFallbackGraphIntegrationTest`: HISTORY bar color == `FORECAST_SUNNY` (amber) for a
     forecast-promoted past day, == `OBSERVED` (red) for a real-actuals past day.
   - `DesktopDailyForecastModelTest`: flag true/false parity.

Bar color is now proven by Robolectric render assertions (`BarDrawnDebug.color`), not just
screenshot inspection: forecast-only history reads as forecast (amber/white), real-actuals
history reads as observed (red).
