# Shared-Code Consolidation (Code-Review Follow-up)

Date: 2026-09-03

## Goal

Reduce duplicated logic in `:app` and `:desktop` by moving pure, cross-platform logic into
`:shared`, and remove thin delegation wrappers / duplicated data types. The project has already
moved most graph geometry, actuals, observations, stats, and util logic into `:shared`; this plan
closes the remaining gaps found during review.

## Findings (source: code review 2026-09-03)

1. **Divergent native-token → condition mappers** (bug risk). `DailyForecastIconResolver` in `:app`
   re-implements OpenMeteo / Tomorrow.io code→condition tables that already exist in `:shared`
   (`OpenMeteoApi.conditionForCode`, `TomorrowIoApi.weatherCodeToCondition`), and the two copies have
   drifted (WMO code 3 → "Cloudy" vs "Overcast"; 95/96/99 → "Thunderstorms" vs "Thunderstorm";
   80-82 → "Rain" vs "Rain Showers"; missing 66/67, 77, 85/86).
2. **`ViewMode` enum duplicated** in `:app` (`WidgetStateManager`) and `:desktop` (`ViewMode.kt`)
   with divergent members and helper names.
3. **Accuracy result types duplicated** — `:app` `stats/AccuracyStatistics.kt` re-declares
   `AccuracyStatistics` (field-identical to `AccuracyPure.AccuracyStatistics`) and a superset
   `DailyAccuracy`, plus `ComparisonStatistics` / `DailyRainAccuracy`.
4. **Thin delegation wrappers** in `:app` that just forward to `:shared`:
   `util/TempUtils.kt`, `util/RainAnalyzer.kt` (also re-declares `RainWindow`/`RainForecast`),
   and part of `util/WeatherTimeUtils.kt`.
5. **`HeaderFormatter.formatSourceIndicator`** and hour/date label formatting are pure Kotlin but
   live only in `:app` (desktop has its own ad-hoc variants).

## Phases (each: implement → full unit-test run → user review → commit on request)

Each phase is independently buildable and testable. Phases 1–4 are mechanical refactors with **no
intended behavior change**. Phase 5 is a behavior-relevant reconciliation and is deliberately last.

### Phase 1 — Remove thin `:app` delegation wrappers
- Delete `app/.../util/TempUtils.kt`; point its 3 call sites at `com.weatherwidget.shared.util.TempUtils`.
- Delete `app/.../util/RainAnalyzer.kt` (and its duplicate `RainWindow`/`RainForecast`); update
  `DailyViewLogic`'s `rainSummaryProvider` defaults to call `shared.util.RainAnalyzer` after mapping
  entities via the existing `toHourlyForecast()`.
- Slim `app/.../util/WeatherTimeUtils.kt` to the entity-specific helpers only
  (`getCurrentHourForecast`, `toHourlyForecastKeyMs`, `MILLIS_PER_DAY`); route the pure
  `alignToNearestHourHalfUp` call sites to `shared.util.WeatherTimeUtils`.

### Phase 2 — Unify `ViewMode` enum into `:shared`
- Add a `@Serializable` `ViewMode` enum in `:shared` with all five members, `isHourly`/`isGraphMode`,
  and `parseOrDefault`/`fromConfig` helpers (keep `@SerialName` values stable for `config.json`).
- Android: replace the `WidgetStateManager` enum with a shared alias/import (name-based persistence
  unchanged).
- Desktop: replace `desktop/ViewMode.kt` with a shared alias/import.

### Phase 3 — Deduplicate accuracy result types
- Extend `shared/.../shared/stats/AccuracyPure.kt` (or a sibling) with the baseline fields,
  `ComparisonStatistics`, and `DailyRainAccuracy`.
- Update `:app` (`AccuracyCalculator`, `RainAccuracyCalculator`, `StatisticsActivity`, adapters) and
  `:desktop` (`StatisticsWindow`, `DesktopAccuracyCalculator`) to use the shared types; delete
  `app/.../stats/AccuracyStatistics.kt`.

### Phase 4 — Move header/label formatting to `:shared`
- Move `formatSourceIndicator` into a shared header formatter; consume it from the three Android
  handlers and align desktop `WidgetHeader` where it intentionally differs (documented).
- Consolidate hour/date label formatting (`formatHourLabel`, `formatDateLabel`,
  `formatMissingHourRanges`) into a shared `HourLabelFormatter`; reconcile with
  `RainAnalyzer.formatHour`.

### Phase 5 — Consolidate native-token → condition mappers (reconciliation)
- Make the shared `OpenMeteoApi` / `TomorrowIoApi` code→condition mappers public and the single
  source of truth; reconcile the divergent strings to the correct WMO/vendor values.
- Delete the private `OpenMeteoConditionMapper` / `TomorrowIoConditionMapper` (and the Android-only
  `visualCrossingIcon` / `openWeatherMapIcon` / `weatherApiIcon` native-token branches where they
  duplicate shared API normalization) from `DailyForecastIconResolver`.
- **Verification:** build, then screenshot the daily/hourly views on the emulator for OpenMeteo and
  Tomorrow.io to confirm icon parity before/after (evidence-first).

## Test command per phase

```bash
scripts/unit-tests.sh
```

(No-args = full default run: `:app` Short/Medium/Long/Localization buckets + `:shared` + `:desktop`.)

## Commit

No commits will be made until the user explicitly asks. Each phase commit will reference this plan
file path.
