# Plan - Fix Overlapping Plateau Labels in Hourly Temperature Graph

Fix the issue where multiple labels for the same temperature value (e.g., 49°) are drawn directly on top of each other when they occur on a plateau.

## Objective
The hourly temperature graph sometimes renders two identical temperature labels at the same position. This happens when multiple "essential" indices (like `actualLowIndex` and `forecastLowIndex`) land on the same plateau. Since both are "protected" from filtering and both use `centerOfRun` for their X-coordinate, they collide perfectly.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`**: Contains the `filterDenseLabelCandidates` logic.
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**: Populates the label candidates and determines which are "protected".

## Proposed Solution
1.  **Fix Tie-Breaking in `GraphLabelPlacementUtils`**: Ensure that even if two candidates have the same priority and strength, one is filtered out if they are within the `nearbyWindow`.
2.  **Deduplicate Plateau Anchors in `TemperatureGraphRenderer`**: Before protecting indices in `explicitAnchors`, ensure that we only keep one index per plateau for each unique formatted value. This aligns with the "cleaner" approach seen in the Cloud Cover graph which protects fewer redundant points.

## Implementation Steps

### 1. Update `GraphLabelPlacementUtils.kt`
- Modify the `competingRetained` search in `filterDenseLabelCandidates` to handle ties.
- If `otherPriority == candidatePriority` and `candidateStrength` is equal, use index as a final tie-breaker (e.g., `otherIdx < candidateIdx`).

### 2. Update `TemperatureGraphRenderer.kt`
- Refactor the candidate population logic in `placeTemperatureLabels`.
- Before adding to `explicitAnchors`, check if a candidate with the same formatted value ("XX.X°") is already slated for the same plateau (using `centerOfRun` to identify plateau membership).
- If a collision at the same plateau/value is detected, only keep the "stronger" role (e.g., `HIGH` > `FORECAST_HIGH`).

## Verification & Testing

### Automated Testing
- Create/Move `TemperatureGraphPlateauOverlapTest.kt` to the test directory.
- Run the test to verify it fails currently and passes after the fix.
- Run `TemperatureGraphLabelPlacementRobolectricTest` to ensure no regressions in existing label placement logic.

### Manual Verification
- Deploy to an emulator.
- Observe a scenario with a temperature plateau (e.g., early morning "dip" that lasts multiple hours).
- Verify that only one label is drawn for the plateau, centered appropriately.
