# Plan: Declutter Precipitation and Cloud Cover Graph Labels

Address visual clutter in hourly precipitation and cloud cover graphs where multiple similar labels (e.g., 10%, 9%, 9%) appear too close to each other. We will ensure the decluttering pass always runs even if the total number of labels is below the maximum cap, and unify advanced labeling rules (soft dips) across both graphs.

## Objective
-   **Fix Filtering Logic**: Update `GraphLabelPlacementUtils.filterDenseLabelCandidates` to ensure the mandatory decluttering threshold (e.g., 5%) is always fully processed, regardless of the `maxCandidates` cap.
-   **Unify Labeling Rules**: Implement `softDipCandidates` in `CloudCoverGraphRenderer.kt` to match `PrecipitationGraphRenderer.kt`.
-   **Plateau Awareness**: Ensure `softDipCandidates` detection is plateau-aware (picks only the center of a flat dip) in both renderers.

## Key Files & Context
-   `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Contains the core filtering logic.
-   `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Current precip rendering logic.
-   `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Current cloud cover rendering logic.

## Implementation Steps

### 1. Fix Mandatory Decluttering in `GraphLabelPlacementUtils`
Modify the early-break condition in `filterDenseLabelCandidates` to allow the first (small) threshold to run completely.

- **Current logic**: `if (retained.size - toRemove.size <= maxCandidates && threshold > 3) break`
- **New logic**: `if (retained.size - toRemove.size <= maxCandidates && threshold > 5) break` (assuming 5 is the mandatory decluttering threshold).

### 2. Refactor `PrecipitationGraphRenderer`
- Update `softDipCandidates` to be plateau-aware. If a dip is flat (e.g., `[20, 9, 9, 20]`), only the center index should be added to `softDipCandidates` and `protectedIndices`.

### 3. Update `CloudCoverGraphRenderer`
- Implement `softDipCandidates` logic (plateau-aware).
- Add `softDipCandidates` to the `candidates` list and `protectedIndices` set (if protection is desired for cloud dips).
- Ensure `DENSE_LABEL_DIFF_THRESHOLDS` and `MAX_CLOUD_PERCENT_LABEL_CANDIDATES` are tuned appropriately (already set to `listOf(8, 12, 16)` and `5` respectively).

### 4. Verify with Reproduction Test
- Run `PrecipitationClutterReproTest.kt` to ensure "10%, 9%, 9%" is reduced to "10%, 9%".

## Verification & Testing

### Automated Tests
-   **Run Repro Test**: `PrecipitationClutterReproTest.kt` (need to add the Category annotation and move it back if I'm in implementation mode).
-   **Unit Tests**:
    -   `PrecipitationGraphRendererTest.kt`
    -   `CloudCoverGraphRendererTest.kt`

### Manual Verification
-   **Emulator Check**: Observe the Precipitation and Cloud Cover graphs in the emulator.
-   **Scenario**: Find or simulate a period with light precipitation or fluctuating cloud cover (e.g., 10%, 9%, 9%) and verify only one 9% label is shown.
