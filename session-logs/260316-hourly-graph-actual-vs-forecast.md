# Session: Hourly Graph — Actual vs Forecast Two-Line Rendering
**Date:** 2026-03-16

## Overview
Implemented and debugged the "actual temperature history (solid) vs forecast (dashed)" two-line design on the hourly temperature graph. Also fixed several related bugs discovered during testing.

---

## Features Implemented (prior session, completed this session)

### New `hourly_actuals` Table (DB migration 31→32)
- `HourlyActualEntity` + `HourlyActualDao`
- All four API sources populate the table: NWS (observations), Open-Meteo (past_days=2), WeatherAPI.com (history endpoint), Silurian (past hours split)
- One-time seed: `seedActualsFromObservationsIfNeeded()` converts existing NWS observations on first upgrade
- Retention cleanup via `deleteOldActuals()` in scheduled sweep

### Two-Line Graph Rendering
- `HourData.temperature` = forecast (drives dashed line)
- `HourData.actualTemperature: Float?` = observed actual (drives solid line, null for future hours)
- `HourData.isActual: Boolean` = whether an actual was found for that hour
- **Past portion:** solid gradient actual line + thin dashed forecast line
- **Future portion:** thin dashed forecast line + ghost/delta line (when delta active)
- Transition at `lastActualIndex` (last hourly bucket with observed data)
- Clip-based rendering: draw full-width bezier path, `canvas.clipRect()` to show only the past portion as solid

---

## Bugs Fixed This Session

### 1. Actual line missing on fresh render
- **Cause:** Widget rendered before actuals were seeded; or widget re-rendered via background update path where `repository=null`
- **Resolution:** Added `ActualsDebug` logging at three layers (query, buildHourDataList, renderGraph) to confirm data flow; intermittent issue resolved after install stabilized

### 2. `repository=null` in navigation path — actual line disappears on back button
- **Root cause:** `handleNavigation()` received `repository` but dropped it when calling `handleGraphNavigation()`:
  ```kotlin
  // BEFORE (broken):
  handleGraphNavigation(context, appWidgetId, isLeft)
  // AFTER (fixed):
  handleGraphNavigation(context, appWidgetId, isLeft, repository)
  ```
- `getHourlyActuals()` was returning 0 rows on every navigation tap → `transitionX=null` → no solid line
- **Lesson:** Parameter threading bug; `handleDailyNavigation` already had it correct, making the asymmetry the giveaway

### 3. Actual line stroke thickness
- Reduced `curveStrokeDp` from `if (heightDp >= 160) 1.5f else 2f` to a flat `1f` to match the forecast line thinness

### 4. "Last observed actual" dot y-mismatch (dot appeared above solid line)
- **Root cause:** Dot was positioned on the **ghost/expected curve** (`smoothedExpectedTemps`). With any delta correction active, the ghost curve sits above/below the actual curve, creating a visible gap between the dot and the end of the solid line.
- **Fix:** Changed dot's y-position to interpolate from `smoothedActualOrForecastTemps` (the actual curve) instead. Dot's x-position (`observedAt`) and triggering condition (`observedAt != null`) preserved unchanged.

### 5. Daily view → temperature graph arrives in NARROW zoom
- **Root cause:** `handleSetView()` reset zoom to WIDE when switching TO daily view, but not when switching FROM daily TO temperature/precipitation/cloud_cover. Any prior NARROW zoom state persisted.
- **Fix:** Added `stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)` in the temperature/precipitation/cloud_cover branch of `handleSetView()`. Graph-internal zoom cycling (tapping the graph) is unaffected.

---

## Key Files Changed
| File | Change |
|------|--------|
| `TemperatureGraphRenderer.kt` | `curveStrokeDp=1f`; dot y from actual curve; `transitionX` at `lastActualIndex` |
| `handlers/WidgetIntentRouter.kt` | Pass `repository` to `handleGraphNavigation`; reset zoom on `handleSetView` |
| `handlers/TemperatureViewHandler.kt` | Added `ActualsDebug` logging |
| `widget/TemperatureGraphRendererFetchDotTest.kt` | Tests pass (no behavior change needed) |

---

## Debugging Approach
Added `ActualsDebug` log tag at three choke points:
1. `getHourlyActuals` call — logs row count, window, source, lat/lon, `repoNull`
2. `buildHourDataList` result — logs total hours and count with `isActual=true`
3. `renderGraph` — logs `lastActualIndex`, `nowX`, `transitionX`, `widthPx`

This triangulated the `repository=null` bug immediately: `repoNull=true` in navigation logs.

---

## Design Decisions Clarified
- **Dot terminology:** "Last observed actual dot" — positioned at `observedAt` timestamp, not at `lastActualIndex` hourly bucket. The two differ because observations can be fetched mid-hour.
- **Transition point:** `lastActualIndex` (last hourly bucket with actual data), NOT `nowX`. Using `nowX` was briefly tried but reverted — the dot (last actual) and solid line endpoint should coincide, not the NOW indicator.
- **Ghost line clip:** Ghost line clips from `transitionX` forward (same anchor as solid line end), ensuring ghost starts where actual data ends.
