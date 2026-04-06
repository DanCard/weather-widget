# Fix Forecast and Actual Lows Plan

## Objective
Address the visual clutter of multiple labels for the same valley (two 53s), remove distracting fractional labels (60.6), and ensure that both the minimum of the actual line and the minimum of the forecast line are always labeled.

## Changes

1. **Explicitly Anchor Actual and Forecast Minimums:**
   In `TemperatureGraphRenderer.kt`:
   - Compute the bounding indices for the actuals portion and the forecast portion using `ctx.effectiveActualEndIndex`.
   - Calculate `actualHighIndex`, `actualLowIndex`, `forecastHighIndex`, and `forecastLowIndex` independently.
   - Add all four indices to the `candidates` list.
   - Include all four indices in `protectedIndices` when passing them to the density filter so they are never completely removed.

2. **Improve Priority & Break Up Adjacent Duplicates (Two 53s):**
   In `GraphLabelPlacementUtils.kt`:
   - Change `candidatePriority` to group both `GLOBAL_MAX` and `GLOBAL_MIN` as Priority 0, and `PEAK`/`VALLEY` as Priority 1.
   - Modify `filterDenseLabelCandidates` so it doesn't prematurely break out of the threshold loop just because it reached `maxCandidates`. It should fully apply the density filter to break up adjacent identical values (like two 53s right next to each other), and only enforce `maxCandidates` at the very end.

3. **Restore Candidate Space:**
   In `TemperatureGraphRenderer.kt`:
   - Restore `MAX_TEMP_LABEL_CANDIDATES` to `8` to allow room for the Start, End, Actual High, Actual Low, Forecast High, and Forecast Low anchors.
   - Revert `MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES` to `1.8f` (from 2.5f) so the true valley at 53° isn't mathematically erased before the labeler even sees it.

## Verification
- Run widget unit tests to confirm the priority and threshold logic holds up.
- Visually verify on the emulator that the graph correctly labels the actuals low, forecast low, and drops the fractional and duplicated labels.
