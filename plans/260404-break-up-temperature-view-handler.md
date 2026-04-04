# Plan: Break up TemperatureViewHandler.kt

**Date**: 2026-04-04
**Status**: In Progress
**Source file**: `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` (1711 lines)

## Problem

TemperatureViewHandler is a 1711-line singleton that mixes 6 distinct responsibilities:
1. Widget update orchestration (updateWidget entry point)
2. Observation blending pipeline (buildHourDataResult, IDW blend)
3. Observation backfill triggering (maybeEnqueueHourlyObservationBackfill)
4. Touch target / PendingIntent wiring (7 setup functions)
5. Text-mode fallback for narrow widgets
6. Header state logging

This makes it hard to reason about any single concern and discourages targeted testing.

## Approach

Extract into top-level functions (not objects) in the same `handlers` package.
One shared util file for `formatHourLabel` (currently duplicated in 3 handlers).

## New Files

### 1. `WidgetFormatUtils.kt` (~10 lines)
- `formatHourLabel(time: LocalDateTime): String`
- Replaces private copies in TemperatureViewHandler, CloudCoverViewHandler, PrecipViewHandler

### 2. `TemperatureHourDataBuilder.kt` (~350 lines)
- `computeSmoothedForecasts(hourlyForecasts, displaySource, smoothIterations): Map<Long, Float>`
- `buildHourDataList(...)` — public @VisibleForTesting
- `buildHourDataResult(...)` — private
- `selectObservationSeries(...)` — internal @VisibleForTesting
- Data classes: `SelectedObservationSeries`, `BuildHourDataResult`, `BlendDebugCollector`
- Helpers: `matchesObservationSource()`, `observationHour()`
- Constants: `HEADER_SMOOTH_ITERATIONS`

### 3. `HourlyObservationBackfill.kt` (~100 lines)
- `evaluateHourlyBackfillNeed(...)` — @VisibleForTesting
- `maybeEnqueueHourlyObservationBackfill(...)` — private (called only from TemperatureViewHandler)
- Data class: `HourlyBackfillDecision`
- Constants: `HOURLY_BACKFILL_COOLDOWN_MS`, `HOURLY_BACKFILL_SOURCE_KEY`

### 4. `TemperatureTouchTargets.kt` (~250 lines)
- `setupNavigationButtons(...)`
- `setupZoomTapZones(...)`
- `setupApiToggle(...)`
- `setupHistoryShortcut(...)`
- `setupHomeShortcut(...)`
- `setupSettingsShortcut(...)`
- `setupCurrentStationsShortcut(...)`
- `setupCurrentTempToggle(...)`
- `positionCenterIcons(...)`
- `HOUR_ZONE_IDS` list

### 5. `TemperatureTextMode.kt` (~80 lines)
- `updateHourlyTextMode(...)`
- `temperatureDeltaHiddenReason(...)`
- `buildHeaderStateLog(...)`
- `formatTemp(...)`, `formatLocation(...)`
- Data class: `HourlyTextSlot`

### 6. `TemperatureViewHandler.kt` (slimmed, ~350 lines)
- `updateWidget(...)` — main entry point, delegates to extracted files
- `scheduleCurrentTempRefinement(...)`, `shouldApplyRefinedHeaderUpdate(...)`
- `scheduleStartupFullGraphRefresh(...)`
- `applyCurrentTempHeader(...)`
- `getCurrentHourForecast(...)`
- `CurrentTempRefinementParams` data class

## Test Impact

~40 call sites across ~10 test files need import updates (mechanical):

| Old | New |
|-----|-----|
| `TemperatureViewHandler.computeSmoothedForecasts` | `computeSmoothedForecasts` (import from TemperatureHourDataBuilder) |
| `TemperatureViewHandler.buildHourDataList` | `buildHourDataList` |
| `TemperatureViewHandler.BlendDebugCollector` | `BlendDebugCollector` |
| `TemperatureViewHandler.HEADER_SMOOTH_ITERATIONS` | `HEADER_SMOOTH_ITERATIONS` |
| `TemperatureViewHandler.evaluateHourlyBackfillNeed` | `evaluateHourlyBackfillNeed` |
| `TemperatureViewHandler.selectObservationSeries` | `selectObservationSeries` |
| `TemperatureViewHandler.HourlyBackfillDecision` | `HourlyBackfillDecision` |

External callers (WidgetIntentRouter, WidgetRenderer, DailyViewHandler) that reference
`TemperatureViewHandler.computeSmoothedForecasts` or `TemperatureViewHandler.updateWidget`
will need import updates for the moved functions.

## Execution Order

1. Create `WidgetFormatUtils.kt`, update all 3 handlers to import `formatHourLabel`
2. Extract `TemperatureHourDataBuilder.kt`
3. Run `./gradlew test`
4. Extract `HourlyObservationBackfill.kt`
5. Run `./gradlew test`
6. Extract `TemperatureTouchTargets.kt`
7. Run `./gradlew test`
8. Extract `TemperatureTextMode.kt`
9. Run `./gradlew test`
10. Slim `TemperatureViewHandler.kt`, update all callers and tests
11. Run `./gradlew test` (final verification)
