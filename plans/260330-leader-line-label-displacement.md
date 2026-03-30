# Plan: Leader Line Displacement for Temperature Label Overlap

## Context

The hourly temperature graph's label collision system has a limited fallback: try above, try below, then force essential labels on with overlap. Around 6 AM (and other crowded areas), this produces visible label stacking. The fix: when a label would collide, **displace it further away from the curve** in incremental steps, drawing a thin **leader line** back to the data point.

## Approach

All changes are in **`TemperatureGraphRenderer.kt`** (~6 localized edits).

### 1. Add leader line paints to `PaintSet` (line ~98, ~120)

Add two new Paint fields: `actualLeaderLinePaint` and `forecastLeaderLinePaint`.

- Style: `STROKE`, `ANTI_ALIAS_FLAG`
- Stroke width: `0.5dp` (hairline, similar to NOW indicator)
- Color: same as label color (`#FFF1A8` / `#C5DCFF`) at **alpha 80** (~31% opacity)
- Created in `ensurePaints()` using existing `withAlpha()` helper (line 91)

### 2. Replace the 2-attempt loop with displacement search (lines 619–633)

Current loop tries 2 positions (preferred side, opposite side). Replace with:

```
for each direction (preferred, then opposite):
    for step in 0..MAX_DISPLACEMENT_STEPS (3):
        offset label by step * labelHeight further from curve
        check on-screen → break direction if off-screen
        check collision → if clear, place and break
        if essential AND last step of last direction → force place
```

- **Step size**: `labelHeight` (≈ `descent - ascent`, the full text height) — labels stack cleanly without partial overlap
- **Max steps**: 3 (configurable constant `MAX_LEADER_DISPLACEMENT_STEPS`)
- **Leader line drawn only when `step > 0`** (label was actually displaced)

### 3. Draw the leader line

When `step > 0`:
- **Start**: `(clampedX, sy)` — the curve point
- **End**: `(clampedX, nearEdgeOfLabel)` — the label edge closest to the curve
  - Label placed above: line goes from `sy` up to `bounds.bottom`
  - Label placed below: line goes from `sy` down to `bounds.top`
- Use `actualLeaderLinePaint` or `forecastLeaderLinePaint` based on `isFuture`

### 4. Update `LabelPlacementDebug` (line 304)

Add field: `displacementSteps: Int = 0`

Update `reason` strings: `"above"` → `"above"` / `"above+2"` (include step count when displaced).

### 5. Essential label behavior improvement

- **Before**: essential labels force at the 2nd position regardless of overlap
- **After**: essential labels get up to `2 × (3+1) = 8` positions to try. Only force at the final position if all fail. The forced position is maximally displaced, so overlap is far less severe.

## Key File

`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

- `PaintSet` class: lines 98–116 — add 2 paint fields
- `ensurePaints()`: lines 120–232 — create the paints
- `placeTemperatureLabels()`: lines 619–633 — replace inner loop
- `LabelPlacementDebug`: lines 304–315 — add `displacementSteps` field
- Constants: add `MAX_LEADER_DISPLACEMENT_STEPS = 3` near other constants (~line 50)
- Reuse: `withAlpha()` at line 91, label colors at lines 58–59

## Verification

1. `./gradlew testDebugUnitTest` — existing label placement tests still pass
2. `./scripts/run-emulator-tests.sh` — instrumented tests pass
3. Visual check on emulator: rebuild widget, observe 6 AM area — labels should stack with leader lines instead of overlapping
4. Test small widget sizes (2x3) to confirm displacement doesn't push labels off-screen
