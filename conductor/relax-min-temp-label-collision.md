# Plan: Relax Collision Detection for Min Temp Labels Below Dips

Ensure that minimum temperature labels (like `ACTUAL_LOW`) are placed below the graph line even when they overlap significantly with the hourly weather icons at the bottom of the widget.

## Objective
- Increase the vertical overlap tolerance with icons specifically for valley-type labels (Lows) when placed in their preferred position (below the curve).
- Prevent these labels from flipping "above" the dip when there is a minor or moderate collision with icons, as drawing above a dip is visually confusing.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Shared collision constants and logic.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Label placement loop for the hourly graph.

## Proposed Solution

### 1. `GraphLabelPlacementUtils.kt`
- Introduce a new constant `MINOR_OVERLAP_ICON_RATIO` (set to `0.85f`) to allow more significant overlap with icons than with other labels.
- The current `MINOR_OVERLAP_HEIGHT_RATIO` is `0.45f`, which is too strict for the crowded icon area at the bottom of the graph.

### 2. `TemperatureGraphRenderer.kt`
- Update the `placeTemperatureLabels` loop to use this new `0.85f` ratio specifically when checking for icon collisions for `VALLEY` / `LOW` / `ACTUAL_LOW` labels in the preferred `below` position.
- This ensures that these labels stay below the dip even when they overlap significantly with the hourly weather icons, which is visually more intuitive than flipping them "above" the dip.

## Implementation Steps

### 1. Update `GraphLabelPlacementUtils.kt`
- Add `const val MIN_OVERLAP_ICON_RATIO = 0.85f`.
- Update `shouldAllowMinorOverlap` or add a new variant for icon-specific overlap.

### 2. Update `TemperatureGraphRenderer.kt`
- Modify the `overlapsIcon` handling in `placeTemperatureLabels`.
- Use the more permissive ratio if `!placeAbove && placement.isValley`.

## Verification & Testing

### Automated Tests
- Create a new Robolectric test `testActualLowLabelStaysBelowDespiteIconOverlap` in `TemperatureGraphLabelPlacementRobolectricTest.kt`.
- Set up a scenario with a deep temperature dip that forces the label into the icon area.
- Verify that `placedAbove` is `false` for the `ACTUAL_LOW` label.

### Manual Verification
- Deploy to the emulator.
- Observe a deep dip in actual temperature.
- Verify the label is drawn below the curve, overlapping with the icon area if necessary, rather than being pushed above the curve.
