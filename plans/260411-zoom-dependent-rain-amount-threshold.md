# Zoom-Dependent Rain Amount Threshold for Precipitation Graph

## Problem
In the precipitation graph's zoomed-in (NARROW) view, rain amount labels only appear when probability reaches 99%. This is too restrictive for the zoomed view where you want more detail — a 97% probability period is functionally near-certain and the user would benefit from seeing the rain amount.

## Solution
Make the high-probability rain amount threshold zoom-dependent:
- **NARROW zoom**: threshold = 97% (show rain amounts for ≥97%)
- **WIDE zoom**: threshold = 99% (unchanged behavior)

## Files Changed

### PrecipitationGraphRenderer.kt
1. Add `highProbThreshold: Int = 99` parameter to `renderGraph()` (line 71)
2. Pass `highProbThreshold` to `findHighProbRainPeriods()` call (line 552)
3. Add `highProbThreshold: Int` parameter to `findHighProbRainPeriods()` (line 800)
4. Replace both `>= 99` checks (lines 804, 807) with `>= highProbThreshold`
5. Update comment on line 543

### PrecipViewHandler.kt
1. Compute `highProbThreshold` from zoom level before the `renderGraph()` call (before line 253)
2. Pass `highProbThreshold` to `renderGraph()`

## Why No Test Changes
All 23 existing test callers use the default parameter value of 99, preserving current behavior. Only the production caller passes the zoom-dependent value.

## Rationale
- NARROW view shows ±2 hours (4h window), so extra rain amount labels won't clutter
- WIDE view shows ±12 hours (24h window), conserving the higher 99% threshold reduces visual noise
- The 97% threshold catches edge cases like the current NWS data showing 97% at 19:00