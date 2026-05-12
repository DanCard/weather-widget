# Task Plan: Rewrite ForecastEvolutionRenderer Y-Axis

## Goal
Fix the Y-axis scaling in the Forecast History activity by implementing a "nice numbers" axis algorithm, eliminating code duplication across the renderer's 3 internal render paths, and adding test coverage.

## Background
`ForecastEvolutionRenderer.kt` (845 lines) is the only renderer that draws Y-axis grid lines and labels. It uses raw data min/max divided into 4 equal steps, producing ugly labels like `72.3, 73.9, 75.5, 77.1, 78.7` instead of round numbers like `70, 75, 80`. The three internal render methods (`renderGraph`, `renderErrorGraph`, `renderSinglePointBarGraph`) share ~60% boilerplate (paint setup, layout calc, grid drawing). Zero test coverage.

## What to Preserve
- Public API: `renderHighGraph`, `renderLowGraph`, `renderHighErrorGraph`, `renderLowErrorGraph` (same signatures)
- `EvolutionPoint` data class
- Bezier curve drawing logic (quadTo smoothing)
- Data bucketing (4-hour snapshot consolidation)
- Color scheme: NWS blue, Meteo green, API actual orange, Location actual red
- Actual-value reference line rendering (dashed + solid)
- Time axis X-positioning (`getTimeX`, `buildTimeTicks`, `formatTimeLabel`)

---

## Phases

### Phase 1: Nice Axis Scale Utility
**Status:** pending

Create pure-Kotlin "nice numbers" algorithm (no Android deps):
- `NiceAxisScale.compute(rawMin, rawMax, targetTickCount=5)` returns `AxisScale(niceMin, niceMax, tickInterval, ticks)`
- Nice interval uses multiples of 1, 2, 2.5, 5, 10
- Expands raw min/max to nearest tick boundaries
- Handles single-value expansion, zero range, negative ranges
- File: `NiceAxisScale.kt` (~60 lines)
- Tests: `NiceAxisScaleTest.kt` (~120 lines, pure JVM, ShortDuration)

### Phase 2: Extract Shared Evolution Style
**Status:** pending

Extract paint creation into cached `PaintSet` (following `TemperatureGraphStyle` pattern):
- File: `EvolutionGraphStyle.kt` (~120 lines)
- Contains all Paint definitions (NWS/Meteo curves, grid, labels, actual lines, error zero line)
- Extract shared layout computation: `EvolutionGraphLayout` data class + `computeLayout()`

### Phase 3: Rewrite ForecastEvolutionRenderer
**Status:** pending

Restructure into shared drawing helpers:
- `drawGridAndAxes()` — grid lines + Y/X labels using NiceAxisScale
- `drawSeries()` — bezier curve + dots for a source series
- `drawActualLine()` — reference line with label
- `tempToY()` — maps temperature to Y pixel using AxisScale

Key changes:
- Replace raw min/max grid with `NiceAxisScale.compute()`
- Replace inline paints with `EvolutionGraphStyle.getPaints()`
- Replace inline layout with `computeLayout()`
- Same public method signatures (zero changes to ForecastHistoryActivity)
- Target: ~500 lines (down from 845)

### Phase 4: Renderer Tests
**Status:** pending

- `ForecastEvolutionRendererTest.kt` (~100 lines, Robolectric, MediumDuration)
- Bitmap dimensions, empty input handling, mode switching, single-point fallback

### Phase 5: Visual Verification
**Status:** pending

- Build, install, verify Y-axis labels are round numbers
- Test both evolution and error modes
- Screenshot comparison

---

## Risks
- Visual regression from range expansion: mitigated by nice axis always expanding range
- Error graph symmetry: pre-compute symmetric range before nice rounding
- Single-point edge case: keep mostly intact, just swap axis scale
