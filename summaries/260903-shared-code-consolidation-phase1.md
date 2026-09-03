# Session summary — Phase 1 of shared-code consolidation: removed thin `:app` delegation wrappers

**Date:** 2026-09-03 · **Plan:** `plans/260903-shared-code-consolidation-review.md`

## Goal

Reduce duplicated logic in `:app`/`:desktop` by moving pure, cross-platform logic into `:shared`.
Phase 1 removed three thin delegation wrappers in `:app` that only forwarded to `:shared`.

## What changed

1. **Deleted `app/.../util/TempUtils.kt`** — a pure pass-through to `shared.util.TempUtils` (it only
   added a `Log.d`). Its 3 main call sites (`ForecastHistoryActivity`, `DailyViewLogic`) and tests
   now use `com.weatherwidget.shared.util.TempUtils` directly.
2. **Deleted `app/.../util/RainAnalyzer.kt`** — the wrapper re-declared `RainWindow`/`RainForecast`
   byte-for-byte identical to the shared types. `DailyViewLogic` now calls the shared `RainAnalyzer`
   through a small `entityRainSummary` helper that maps `HourlyForecastEntity` → `HourlyForecast`.
3. **Slimmed `app/.../util/WeatherTimeUtils.kt`** — dropped the pure `alignToNearestHourHalfUp`
   passthrough (callers use `shared.util.WeatherTimeUtils` directly); kept the entity-specific
   `getCurrentHourForecast` / `toHourlyForecastKeyMs` / `MILLIS_PER_DAY`.
4. **Moved `RainAnalyzerTest.kt` (31 pure tests) from `:app` to `:shared`**, converted to build the
   shared `HourlyForecast` model directly (replacing `TestData.toEpoch` with inline
   `LocalDateTime`/`ZoneId` conversion).
5. **Updated the two remaining RainAnalyzer tests** for the shared model:
   - `RainAnalyzerQueryWindowTest.kt` (Room/Robolectric) maps entities → shared before `analyzeDay`.
   - `RainAnalyzerIntegrationTest.kt` (androidTest) maps entities → shared before `getRainSummary`.
6. **Preserved logging.** The wrapper's `android.util.Log.d` breadcrumbs were ported into the shared
   `RainAnalyzer` using the cross-platform `com.weatherwidget.shared.util.Log`, so the
   `"rain hours for …"` logcat line that the instrumented test asserts on still fires. (The
   redundant `"Found N rain windows"` line in `analyzeDay` was dropped — that path is only reachable
   from tests, never production.)

## Verification

- `scripts/unit-tests.sh` (full default run): **3918 tests passed, 0 failed** —
  985 short + 40 localization + 66 medium + 1014 long (app); 1443 shared; 370 desktop.
  The 31 relocated tests shifted app → shared with net-zero total.
- `./gradlew :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.

## Next phases (pending review/commit)

- Phase 2 — unify `ViewMode` enum into `:shared`.
- Phase 3 — deduplicate accuracy result types.
- Phase 4 — move header/label formatting to `:shared`.
- Phase 5 — consolidate native-token → condition mappers (divergence reconciliation).
