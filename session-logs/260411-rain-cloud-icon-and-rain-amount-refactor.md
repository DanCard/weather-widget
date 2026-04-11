# 260411 Rain Cloud Icon Fix and Rain Amount Refactor

## Date
April 11, 2026

## Tasks

### 1. Rain Cloud Icon Missing in Zoomed Precipitation Graph

**User prompt:**
> Emulator: the rain cloud icon is missing in the background. zoomed in rain chance graph.

**Investigation:**
- Examined `PrecipitationGraphRenderer.kt` rain watermark placement logic (lines 703-785)
- Added diagnostic logging to find placement failures
- Log output showed both placement strategies (ABOVE and BELOW curve) failed:
  - Strategy 1 (ABOVE): `belowCurveOk=false` — icon too close to high precip curve
  - Strategy 2 (BELOW): `labelIntersects=true` — icon overlapped probability labels

**Root cause:** 52px icon size on narrow graph (731x308) left insufficient gaps.

**Initial fix attempt:** Added position bonus weighting to prefer high+left positions. Still placed low in practice.

**Final fix:** Replaced complex scoring with simple top-to-bottom, left-to-right grid scan:
```kotlin
val xFractions = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f)
val yFractions = listOf(0.12f, 0.25f, 0.38f, 0.5f, 0.65f, 0.8f)
for (yFrac in yFractions) {
    for (xFrac in xFractions) {
        // Check if position is clear (no label overlap, within bounds)
        // First clear position wins
    }
}
```

**Testing:** Added `WatermarkPlacementDebug` callback and 5 JUnit tests:
- `watermark prefers high position when no labels block`
- `watermark prefers left position when no labels block`
- `watermark scans top-to-bottom left-to-right`
- `watermark still placed with high precipitation and many labels`
- `watermark not placed with fewer than 3 data points`

---

### 2. Rain Amount Threshold Change (97% → 95%)

**User prompt:**
> Lower the rainfall % threshold in zoomed in precipitation graph from 97% to 95% which decides whether rainfall amounts should be displayed.

**Changes:**
- `PrecipViewHandler.kt:252`: Changed `highProbThreshold` from conditional (97 for NARROW, 99 for WIDE) to constant 95
- Updated comment in `PrecipitationGraphRenderer.kt:553`

---

### 3. Rain Amount Window: Show Total for Wider Time Range

**User prompt:**
> Emulator : what logic does the zoomed in view use to chose rain fail amount between 6p to 7pm. I'd like to see a wider time range.

**Analysis:** Current logic only showed rain amount for consecutive hours meeting threshold (95%+). With narrow view showing ~5 hours, this was too short.

**Implementation:** Added `rainAmountWindowHours` parameter to `renderGraph()`:
- `findFixedWindowRainPeriods()`: finds all sliding windows of size N, picks one with highest total precip
- NARROW: 5-hour window → showed "5p-9p" instead of "6p-7p"
- Amount jumped from ~0.14in to ~0.36in (closer to NWS prediction of "half inch to 3/4 inch")

**User prompt:**
> Emulator: The rain amount overlaps with label.

**Fix:** Added position scanning to avoid probability label overlap:
```kotlin
val positionRatios = listOf(0.75f, 0.5f, 0.85f, 0.35f, 0.95f)
for (ratio in positionRatios) {
    val candidateBounds = ...
    val overlapsProbLabel = drawnLabelBounds.any { RectF.intersects(it, candidateBounds) }
    if (!overlapsProbLabel) { ... }
}
```

**User prompt:**
> Overlap text doesn't need to include time frame. Can just assume it covers the graph time range.

**Simplification:** Removed time range from label (e.g., ".36in" instead of ".36in 5p-9p")

---

### 4. Unify Narrow and Wide View Settings

**User prompt:**
> Lets remove the exception for zoomed in view versus wide view. Make them the same for showing the rain amount, threshold 95%

**Changes:**
- Both views now use: `highProbThreshold = 95`
- Both views now use: `rainAmountWindowHours = hours.size` (full graph width)

---

## Files Modified

| File | Changes |
|------|---------|
| `PrecipitationGraphRenderer.kt` | Added `WatermarkPlacementDebug` data class, `rainAmountWindowHours` parameter, `findFixedWindowRainPeriods()`, position-scanning for overlap avoidance |
| `PrecipViewHandler.kt` | Changed to unified settings: `highProbThreshold = 95`, `rainAmountWindowHours = hours.size` |
| `PrecipitationGraphWatermarkTest.kt` | Rewrote with 5 JUnit tests using new callback |

## Tests

All precipitation-related tests pass:
- `PrecipitationGraphWatermarkTest`: 5 tests
- `PrecipitationGraphRendererTest`: 8 tests
- `PrecipitationGraphRendererRobolectricTest`: 3 tests
- `PrecipitationGraphRendererLogRoboTest`: 4 tests
- `PrecipTouchRoutingRoboTest`: 1 test
- `PrecipProbabilityTouchRoutingRoboTest`: 2 tests

## Commit

```
Simplify rain cloud watermark placement and unify rain amount logic

- Replace complex distance-based scoring with simple grid scan (top-to-bottom, left-to-right)
- Add WatermarkPlacementDebug callback and 5 tests for watermark placement
- Change rain amount to use fixed window (5h for zoomed, graph width for wide)
- Remove time range from rain amount label
- Fix rain amount overlap with probability labels via position scanning
- Unify NARROW and WIDE settings: 95% threshold, full graph width window
```

---

## Summary

This session addressed two main areas:

1. **Rain cloud icon** — Fixed missing icon in zoomed precipitation graphs by replacing complex placement scoring with simple top-to-bottom grid scan. Added test coverage via callback.

2. **Rain amount display** — Refactored to show total precipitation for the graph's time width rather than just high-probability streaks. Added overlap avoidance, removed redundant time range text, and unified settings across zoom levels.