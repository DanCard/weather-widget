# Plan: Refine Precipitation and Cloud Cover Graph Label Decluttering

Address remaining visual clutter in hourly precipitation and cloud cover graphs where multiple "protected" labels (soft dips) or less-significant peaks (e.g., 10% vs 9% valley) appear too close to each other.

## Objective
-   **Prune Redundant Protected Indices**: Update `GraphLabelPlacementUtils.filterDenseLabelCandidates` to only fully protect Global Max/Min. Other "protected" indices (like soft dips or zero-runs) should be able to prune each other if they are within the same window.
-   **Prioritize Valleys at Low Levels**: Update `candidatePriority` or filtering logic to favor Valleys over Peaks when both are below a "light" threshold (e.g., 15%). This addresses "10% vs 9%" where 10% is technically a peak but 9% is a more informative valley.

## Key Files & Context
-   `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Contains the filtering and priority logic.
-   `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Current precip rendering logic.
-   `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Current cloud cover rendering logic.

## Implementation Steps

### 1. Refine Protection Logic in `GraphLabelPlacementUtils`
Split `protectedAnchors` into two tiers:
- **Tier 1 (Immovable)**: Global Max and Global Min. These are never removed.
- **Tier 2 (Prunable against each other)**: `protectedIndices` passed from the renderer (Soft Dips, Zero Runs). These can prune each other if they are nearby, but they should still have higher priority than standard "optional" candidates (peaks/valleys that aren't soft).

### 2. Update Prioritization Logic
Modify `candidatePriority` to accept a `thresholdValue` or change the logic based on the value:
- If `value < 15`, `VALLEY` should have higher priority (smaller number) than `PEAK`.
- This ensures that for a `[10, 9, 10]` sequence, the `9%` valley is kept instead of the `10%` peaks.

### 3. Verify with Reproduction Test
- Run `PrecipitationClutterReproTestV2.kt` to ensure:
    - `[40, 29, 40, 29, 40]` results in only one `29%` label.
    - `[10, 9, 10]` results in only one `9%` label.

## Verification & Testing

### Automated Tests
-   **Run Repro Test**: `PrecipitationClutterReproTestV2.kt`.
-   **Regression Testing**:
    -   `PrecipitationGraphRendererTest.kt`
    -   `CloudCoverGraphRendererTest.kt`
    -   `TemperatureGraphLabelPlacementRobolectricTest.kt` (ensure no regressions in temp graph)

### Manual Verification
-   **Emulator Check**: Observe the Precipitation and Cloud Cover graphs.
-   **Scenario**: Find a period with light, fluctuating rain (e.g., 10%, 9%, 10%) and verify the valley is prioritized.
