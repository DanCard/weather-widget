# TemperatureViewHandler.kt Code Review — 2026-04-04

## Overview

Review of `TemperatureViewHandler.kt` (1713 lines), the rendering coordinator for the hourly temperature graph view. It is a singleton (`object`) that manages observation blending via IDW, two-phase startup rendering, touch target wiring, and graph rendering.

## Critical Issues

### 1. Hardcoded request codes collide with WidgetRequestCodes scheme (lines 352, 918, 1063, 1111)

All four view handlers use `appWidgetId * 100 + N` while `WidgetRequestCodes` uses `id * 10000 + N`. Multiple handlers assign the same `appWidgetId * 100 + 700` for different intents (history shortcut vs other). PendingIntents overwrite each other across handlers.

**Fix:** Add missing constants to `WidgetRequestCodes` and migrate all handlers.

### 2. updateWidget doesn't implement WidgetViewHandler (line 146)

`DailyViewHandler` implements the interface; this handler has a completely different 13-param signature. The router cannot treat handlers polymorphically.

**Fix:** Deferred — would require significant router refactoring.

## Design Issues

### 3. God object — 1713 lines, updateWidget alone ~460 lines (lines 259-715)

Mixes rendering, touch target setup, observation blending orchestration, backfill scheduling, debug logging, and two-phase startup coordination.

**Fix:** Partial — extract what's feasible without breaking the router contract.

### 4. scheduleCurrentTempRefinement has 18 parameters (lines 717-734)

**Fix:** Extract to a parameter data class `CurrentTempRefinementParams`.

### 5. DRY violation: forecastsByTime source-priority logic repeated 3x

Lines 182-189 (`computeSmoothedForecasts`), 1205-1212 (`buildHourDataResult`), 1606-1611 (`updateHourlyTextMode`) all do groupBy + preferred/gap/fallback resolution.

**Fix:** Extract to a shared utility function.

### 6. Inconsistent visibility ordering — positionCenterIcons fights setup*Shortcut (lines 1127-1146)

Setup methods set views VISIBLE, then positionCenterIcons immediately hides one set. Fragile to reordering.

**Fix:** Setup methods should only bind PendingIntents; visibility should be set in one place.

## Correctness Concerns

### 7. asyncScope has no lifecycle management + stale comment (line 156)

Comment says 900ms delay but constant is `STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 200L`.

**Fix:** Update the comment to match reality.

### 8. lastActual forward-fill carries stale data across days (lines 1324-1326, 1392-1412)

No staleness limit on how far back the seed observation can be.

**Fix:** Deferred — behavioral change needs careful testing.

### 9. No top-level error boundary in updateWidget

If `TemperatureGraphRenderer.renderGraph` or any intermediate step throws, the widget is left in a partial state.

**Fix:** Wrap graph rendering in try/catch, push fallback views on failure.

## Minor Issues

### 10. buildHourDataList is a trivial wrapper (lines 1170-1190)

Exists only for test convenience. Fine to keep.

### 11. Inline fully-qualified type references throughout

Several types used with full package paths instead of imports.

**Fix:** Add imports, remove inline qualifiers.

### 12. selectObservationSeries appears unused in production (line 1460)

Only called from tests. Dead code in production. Keep for testing.

### 13. Quad private data class (line 1712)

4-tuple with generic names. Should be a named data class.

**Fix:** `HourlyTextSlot` or similar.

## Summary

| Severity | Count | Key Theme |
|----------|-------|-----------|
| Critical | 2 | Request code collisions, interface non-conformance |
| Design   | 4 | God object, parameter explosion, DRY, visibility fragility |
| Correctness | 3 | Stale comment, unbounded forward-fill, no error boundary |
| Minor    | 4 | Dead code, readability, naming |

## Implementation Results

All 8 planned items implemented. Tests pass after each batch.

### Changes Made

1. **Request codes → WidgetRequestCodes** (#1)
   - Added `cycleZoomZone`, `history`, `currentStations`, `iconViewToggle` to `WidgetRequestCodes`
   - Migrated `TemperatureViewHandler`, `PrecipViewHandler`, `CloudCoverViewHandler` to use centralized codes
   - Moved `BASE_SETTINGS` from 900 to 950 to avoid collision with `BASE_ICON_VIEW_TOGGLE` (900)

2. **Extract forecastsByTime DRY utility** (#5)
   - New file: `ForecastSourcePriority.kt` with `resolveForecastsByTime()` function
   - Replaced 3 occurrences in `TemperatureViewHandler`, 1 in `DailyViewHandler`

3. **Fix stale comment** (#7)
   - Updated file header comment: "900ms" → "200ms" to match `STARTUP_FULL_GRAPH_REFRESH_DELAY_MS`

4. **Replace fully-qualified types** (#11)
   - Added imports: `ObservationEntity`, `WeatherRepository`, `ForecastHistoryActivity`, `CurrentTemperatureDeltaState`, `GraphRenderUtils`, `ObservationResolver`
   - Removed all inline `com.weatherwidget.*` references from code body
   - Cleaned up unused imports (`BlendObservationResult`, `StationSeriesStats`, etc.)

5. **Replace Quad with named data class** (#13)
   - `Quad<A, B, C, D>(first, second, third, fourth)` → `HourlyTextSlot(labelId, iconId, tempId, lowId)`

6. **Parameter object for scheduleCurrentTempRefinement** (#4)
   - New `CurrentTempRefinementParams` data class with 17 fields
   - `scheduleCurrentTempRefinement` now takes single params object

7. **Fix visibility ordering** (#6)
   - Removed `setViewVisibility(VISIBLE)` from `setupHistoryShortcut`, `setupHomeShortcut`, `setupCurrentStationsShortcut`
   - `positionCenterIcons` is now the single authority for center icon visibility

8. **Error boundary around graph rendering** (#9)
   - Already implemented — `try/catch` wraps `renderGraph`, falls back to text mode on failure

### Deferred

- Interface conformance (#2) — requires router refactoring
- God object decomposition (#3) — large scope
- lastActual forward-fill staleness (#8) — behavioral change
