# Declutter Actual Line Graph Labels Plan

## Objective
Apply the label decluttering rules developed for the Cloud Cover graph to the actual temperature line graph in `TemperatureGraphRenderer.kt` to reduce visual clutter and prevent awkward overlapping.

## Changes

1. **Stop Protecting Actual Line Local Extrema from Dense Filtering:**
   Currently, all `significantLocalExtrema` are passed to `GraphLabelPlacementUtils.filterDenseLabelCandidates` via the `protectedIndices` parameter. This prevents dense label filtering from pruning closely packed local extrema on the actual line graph, causing visual clutter.
   - **Update:** Only pass forecast local extrema to `protectedIndices`. Filter out any indices that fall within the actual line graph (i.e., `<= ctx.effectiveActualEndIndex`).
   - This ensures actual line graph extrema are subjected to the dense label difference thresholds, matching the Cloud Cover rules.

2. **Apply Left-Edge Suppression:**
   `TemperatureGraphRenderer.kt` computes `suppressLeftEdgeLabel` (which skips the left edge label if there is a nearby lower valley) but never actually uses it.
   - **Update:** Inside the `for (idx in filteredDistinct)` loop, check if `idx == 0 && suppressLeftEdgeLabel`. If true, skip the label and log the reason. This mirrors the Cloud Cover graph's edge suppression logic.

3. **Increase Decluttering Aggressiveness (Refinement):**
   To further reduce clutter (e.g., preventing labels for 53° and 55° being shown together on the actual line):
   - **Update `TemperatureGraphRenderer.kt`:**
     - Set `MAX_TEMP_LABEL_CANDIDATES = 5` (was 6) to match the Cloud Cover graph's limit.
     - Set `DENSE_TEMP_DIFF_THRESHOLDS = listOf(3, 4, 5)` (was `2, 3, 4`) to eliminate smaller temperature fluctuations in dense areas.
   - **Update `GraphLabelPlacementUtils.kt`:**
     - Change the filtering comparison logic from `valueDifference < threshold` to `valueDifference <= threshold`. This ensures that a 2° difference is eliminated when the threshold is 2, rather than requiring a 3° threshold to catch it.


## Verification
- Unit tests: Ensure tests relying on the start label account for left-edge suppression (or update test data to not trigger suppression).
- Visual verification: Observe the hourly temperature graph on the emulator to ensure the actual line graph is less cluttered and doesn't suffer from dense local extrema or awkward left-edge placements.
