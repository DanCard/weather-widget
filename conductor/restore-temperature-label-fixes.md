# Plan: Restore Temperature Graph Label Fixes

A previous session successfully "decluttered" the temperature graph's actuals line by applying the Cloud Cover density filtering logic and making several geometry and placement improvements. Unfortunately, those changes were wiped out. This plan will systematically restore them.

## Objective
- **Filter Actuals Clutter**: Expose the actual temperature labels to the same density filtering logic (`filterDenseLabelCandidates`) that governs the Cloud Cover graph.
- **Smart Tie-Breaking**: Fix the bug in `GraphLabelPlacementUtils` where identical values on a plateau fail to filter each other out.
- **Prioritize Lower Dips**: Ensure that when multiple dips exist near each other, the lowest one is prioritized for the "below" position.
- **Center Plateaus**: Make sure `FORECAST_LOW` and `LOCAL` extrema labels are horizontally centered over multi-hour plateaus.
- **Increase Overlap Tolerance**: Allow labels like `LOW` to slightly overlap the hour icons if it helps them stay under the curve.

## Implementation Steps

### 1. `GraphLabelPlacementUtils.kt` (Tie-Breaker Fix)
- In `filterDenseLabelCandidates`, update the tie-breaker to use `>=` instead of `>` so that adjacent identical values are properly pruned.
  ```kotlin
  candidateStrength(otherIdx, items, globalMaxIdx, globalMinIdx, valueFunction) >=
  candidateStrength(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction)
  ```

### 2. `TemperatureGraphRenderer.kt` (Filtering & Tuning)
- Reduce `MAX_TEMP_LABEL_CANDIDATES` from 6 to 5.
- Increase `MINOR_OVERLAP_HEIGHT_RATIO` from 0.15f to 0.35f to let `LOW` labels fit better.
- In `placeTemperatureLabels`, update the `filterDenseLabelCandidates` call to NOT protect `significantLocalExtrema`. This forces them to be filtered out if they are too dense.

### 3. `TemperatureGraphRenderer.kt` (Sorting Candidates)
- Before the placement loop, sort `specialCandidates` by role priority and temperature value:
  - **High Priority (0):** `HIGH`, `LOW`, `FORECAST_HIGH`, `FORECAST_LOW`.
  - **Medium Priority (1):** `LOCAL`, `ACTUAL_END`.
  - **Low Priority (2):** `START`, `END`.
- Tie-breakers:
  - For peaks (Highs/Peaks), sort by descending temp (highest temp placed first).
  - For dips (Lows/Valleys), sort by ascending temp (lowest temp placed first).

### 4. `TemperatureGraphRenderer.kt` (Centering & Peak/Valley Detection)
- Update `sx` calculation to center `FORECAST_LOW` and `LOCAL` roles over plateaus by passing them to `centerOfRun`.
- Update `isValley` and `isPeak` logic to look for the first non-identical value on the left AND right. This ensures proper "above" or "below" classification even if the label is centered on a flat section.

## Verification
- Run `./gradlew test` to ensure existing placement and filtering logic still passes.
- We will verify in the emulator that the "actual" line is no longer cluttered with redundant intermediate fluctuations.
