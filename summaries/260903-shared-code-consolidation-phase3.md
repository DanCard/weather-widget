# Session summary — Phase 3 of shared-code consolidation: deduplicated accuracy result types

**Date:** 2026-09-03 · **Plan:** `plans/260903-shared-code-consolidation-review.md`

## Goal

Remove the app-only accuracy data classes that duplicated shared `AccuracyPure` types, and relocate
the remaining cross-platform models into `:shared`.

## What changed

1. **New `shared/.../shared/stats/AccuracyModels.kt`** — `ComparisonStatistics` (per-source
   `AccuracyPure.AccuracyStatistics?`) and `DailyRainAccuracy`, both previously app-only.
2. **Reused `AccuracyBreakdown.DailyResult`** instead of the app's duplicate `DailyAccuracy`. They
   were field-for-field identical (including the `baselineSourceId`/`baselineStationId`/
   `baselineFellBackToBlend` provenance fields). `AccuracyCalculator.getDailyAccuracyBreakdown`
   now returns `List<DailyResult>` directly — the whole entity→`DailyAccuracy` mapping was deleted.
3. **Reused `AccuracyPure.AccuracyStatistics`** instead of the app's field-identical duplicate.
   `calculateAccuracy` collapsed from a 20-line field-by-field copy to a one-liner calling
   `AccuracyPure.computeStatistics(...).map { it.toPureDailyAccuracy() }`.
4. **Deleted `app/.../stats/AccuracyStatistics.kt`** (4 data classes: `AccuracyStatistics`,
   `DailyAccuracy`, `ComparisonStatistics`, `DailyRainAccuracy`).
5. Updated imports/call sites in `StatisticsActivity`, `DailyAccuracyAdapter`,
   `DailyRainAccuracyAdapter`, and two test files to the shared types.

## Verification

- `scripts/unit-tests.sh`: **3918 tests passed, 0 failed** (985 short + 40 localization + 66 medium
  + 1014 long app; 1443 shared; 370 desktop).
- `./gradlew :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.

## Next phases (pending review/commit)

- Phase 4 — move header/label formatting to `:shared`.
- Phase 5 — consolidate native-token → condition mappers (divergence reconciliation).
