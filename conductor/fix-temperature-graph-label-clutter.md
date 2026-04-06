# Plan: Fix Temperature Graph Label Clutter

The temperature graph currently displays too many labels, especially on the "actuals" (past) portion of the graph. This leads to overlaps and a cluttered appearance. We will apply labeling rules from the cloud cover graph to the actuals series to reduce noise.

## Objective
- Reduce temperature label clutter by applying more aggressive filtering to both forecast and actuals series.
- Keep the existing leader line and displacement logic to maintain clarity for essential peaks.
- Un-protect significant local extrema in the filter to ensure they are merged/pruned when too close to other labels.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: The main rendering logic for the temperature graph.
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: The reference for "clean" labeling rules.
- `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Shared utility for candidate filtering.

## Implementation Steps

### 1. Update Candidate Filtering in `TemperatureGraphRenderer`
- Change `MAX_TEMP_LABEL_CANDIDATES` from 6 to 5 to match the cloud cover graph.
- In `placeTemperatureLabels`, remove the `protectedIndices = significantLocalExtrema.toSet()` parameter from the `filterDenseLabelCandidates` call.
- **Fix Tie-Breaking Bug**: Update `GraphLabelPlacementUtils.filterDenseLabelCandidates` to handle cases where two candidates have the same priority and strength (e.g., adjacent points on a temperature plateau). By using a non-strict inequality (`>=`) or consistent tie-breaker (like index comparison), we ensure that redundant labels are pruned even when their values are identical.

### 2. Refine Label Placement (Actuals Line)
- **Keep Leader Lines**: As per user feedback, we will NOT disable leader lines for the actuals series. We will continue to allow them to displace to avoid collisions.
- **Reduce Clutter via Filtering**: The primary reduction in clutter will come from the updated candidate filtering in Step 1 (un-protecting `significantLocalExtrema`). This ensures that even "essential" roles on the actuals line won't double up if they are too close in value/time.
- **Role Prioritization**: Ensure that if a point on the actuals line is both a "significant local extreme" and near another candidate, the filter correctly favors the more significant one (Global Max/Min > Peak/Valley).

### 3. Allow More Overlap for LOW/VALLEY Labels
- **Increase Overlap Threshold**: Update `MINOR_OVERLAP_HEIGHT_RATIO` in `TemperatureGraphRenderer.kt` from 0.15f (15%) to 0.35f (35%) specifically for `LOW` and `VALLEY` roles. This allows labels at the bottom of the graph to overlap slightly more with the hour icons/labels if it means they can be placed in their preferred "below" position.
- **Specific Handling for 50.4**: By increasing this ratio, the 8.99px icon overlap for the 50.4° label (which has a ~30px label height) will fall under the new ~10.5px threshold, allowing it to be placed below the line as requested.

### 4. Prioritize Extremes by Value for Below Placement
- **Sort Candidates**: In `placeTemperatureLabels`, sort `specialCandidates` before the placement loop.
- **Priority Logic**:
    - Assign a high priority (0) to `HIGH`, `LOW`, `FORECAST_HIGH`, and `FORECAST_LOW` roles.
    - Assign a medium priority (1) to `LOCAL` and `ACTUAL_END` roles.
    - Assign a low priority (2) to `START` and `END` roles.
- **Tie-breaker for Dips**: For roles that prefer "below" placement (Valleys/Lows), sort by temperature ascending (lower temperature = processed first).
- **Tie-breaker for Peaks**: For roles that prefer "above" placement (Peaks/Highs), sort by temperature descending (higher temperature = processed first).
- **Benefit**: This ensures that the 50.4° label is processed before the 51° label, giving it first dibs on the "below" position, even if it has a later index.

## Migration & Rollback
- This is a UI-only change with no database or persistent state impact. Rollback is as simple as reverting the code changes.
