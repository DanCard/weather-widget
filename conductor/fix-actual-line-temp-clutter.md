# Fix Actual Line Temperature Clutter Plan

## Objective
Reduce clutter on the actual temperature line graph (e.g., labels 53, 55, and 60.6 appearing too close together) by adjusting the significance thresholds for local extrema and expanding the window used for density filtering.

## Changes

1. **Increase Extrema Prominence Threshold:**
   In `TemperatureGraphRenderer.kt`, change `MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES` from `1.5f` to `2.5f`. This ensures that minor temperature fluctuations (like a 2-degree bump from 53 to 55) are not classified as significant extrema, naturally removing them from the candidate pool.

2. **Parameterize Dense Label Window:**
   In `GraphLabelPlacementUtils.kt`:
   - Add `nearbyWindow: Int = NEARBY_LABEL_WINDOW` to `filterDenseLabelCandidates`.
   - Add `nearbyWindow: Int = NEARBY_LABEL_WINDOW` to `shouldSuppressLeftEdgeLabel`.
   - Replace hardcoded `NEARBY_LABEL_WINDOW` usages inside these functions with the `nearbyWindow` parameter.

3. **Widen Density Window for Temperature Graph:**
   In `TemperatureGraphRenderer.kt`:
   - When calling `GraphLabelPlacementUtils.filterDenseLabelCandidates`, pass `nearbyWindow = 5`.
   - When calling `GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel`, pass `nearbyWindow = 5`.
   This allows the dense label filtering algorithm to identify and prune conflicting labels over a wider 5-hour horizontal window, reducing visual crowding.

## Verification
- Unit tests: Ensure tests pass and the label placement logic still functions correctly.
- Visual check: Run in emulator to verify the actual temperature line no longer displays 53 and 55 clustered together.
