# Restore Extrema Dip and Fix Clutter Logic Plan

## Objective
Restore the "extrema dip" (e.g., 53°) that was lost due to overly aggressive prominence filtering, while still solving the clutter issue by improving the "significance" logic in the dense label filter.

## Changes

1. **Lower Prominence Threshold:**
   In `TemperatureGraphRenderer.kt`, revert `MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES` to `1.8f` (from `2.5f`). This ensures that 2-degree dips are detected as candidates again.

2. **Restore Candidate Capacity:**
   In `TemperatureGraphRenderer.kt`, revert `MAX_TEMP_LABEL_CANDIDATES` to `6` (from `5`). This gives more room for local extrema when the graph is complex.

3. **Level the Priority Playing Field:**
   In `GraphLabelPlacementUtils.kt`, update `candidatePriority` to:
   - Priority 0: `GLOBAL_MAX`, `GLOBAL_MIN` (Both are equally vital anchors).
   - Priority 1: `PEAK`, `VALLEY` (Both are equally vital local features).
   - Priority 2: `EDGE` (Start/End are useful but subordinate to extrema).

4. **Improve Significance Metric (`candidateStrength`):**
   In `GraphLabelPlacementUtils.kt`, update `candidateStrength` to use the **distance from the global range center** as the metric for strength.
   - Calculate `center = (globalMaxVal + globalMinVal) / 2`.
   - `strength = abs(value - center)`.
   - This ensures that a deep valley (like 53° in a 50-70° range) is recognized as "stronger" and more significant than a minor intermediate peak (like 55°), causing the minor peak to be pruned instead of the valley.

5. **Update Tests:**
   Adjust `TemperatureGraphRendererLabelPlacementTest.kt` and `TemperatureGraphLabelPlacementRobolectricTest.kt` to account for the improved priority and strength logic.

## Verification
- Unit tests: Run full widget suite.
- Visual check: Verify the 53° dip is restored and the 55° clutter is gone.
