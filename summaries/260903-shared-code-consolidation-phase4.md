# Session summary — Phase 4 of shared-code consolidation: header/label formatting moved to `:shared`

**Date:** 2026-09-03 · **Plan:** `plans/260903-shared-code-consolidation-review.md`

## Goal

Move pure header/label formatting out of `:app`/`:desktop` into `:shared`, and make one shared formatter
the single source of truth for hour/date labels across all three modules.

## What changed

1. **New `shared/.../shared/util/HeaderFormatter.kt`** — `formatSourceIndicator` moved verbatim from
   `:app` (pure Kotlin, no behavior change). The three Android hourly-view handlers now import it
   from `:shared`. Desktop's header intentionally keeps its simpler short-source-label rendering
   (documented in the shared kdoc).
2. **New `shared/.../shared/util/HourLabelFormatter.kt`** — `hourLabel`/`hourLabelParts`/`dateLabel`/
   `missingHourRanges`, moved from `app/.../widget/handlers/WidgetFormatUtils.kt`. Added an
   `hourLabel(hour: Int)` overload so desktop's integer-hour call sites share the same core.
3. **Reconciled `RainAnalyzer.formatHour`** (two-char meridiem "3pm") to delegate to
   `HourLabelFormatter.hourLabel` + `"m"`, so the one/two-char variants can never drift.
4. **Desktop `DesktopGraphUtils.formatHourLabel`/`formatDateLabel`** now delegate to the shared
   `HourLabelFormatter` instead of re-implementing the hour math (`hour % 12`) and weekday formatting.
5. **Removed the `formatPrecipAmount` pass-through** from `WidgetFormatUtils` (it already just
   delegated to shared `DailyRainLabels.formatPrecipAmount`); the two call sites now call
   `DailyRainLabels.formatPrecipAmount` directly.
6. **Deleted** `app/.../util/HeaderFormatter.kt`, `app/.../widget/handlers/WidgetFormatUtils.kt`, and
   the app test `WidgetFormatUtilsTest.kt`. Its hour/date/missing-hour tests moved to
   `shared/.../shared/util/HourLabelFormatterTest.kt`; the `dateLabelMillis` tests moved to
   `app/.../widget/handlers/DateLabelMillisTest.kt` (that function stays app-side). The 8
   `formatPrecipAmount` locale tests were dropped as already covered by `shared` `DailyRainLabelsTest`.

## Verification

- `scripts/unit-tests.sh`: **3913 tests passed, 0 failed** (987 short + 22 localization + 66 medium
  + 1014 long app; 1454 shared; 370 desktop). Net −5 tests: 8 redundant precip tests dropped, 11
  label tests moved to `:shared`, 2 `dateLabelMillis` tests re-homed in `:app`.
- `./gradlew :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.

## Next phases (pending review/commit)

- Phase 5 — consolidate native-token → condition mappers (divergence reconciliation).
