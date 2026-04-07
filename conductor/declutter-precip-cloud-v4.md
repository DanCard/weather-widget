# Plan: Refine Label Pruning with Immovable Anchors

Refine visual clutter in hourly graphs by distinguishing between "immovable" anchors (never pruned) and "soft protected" anchors (can prune each other). This fixes regressions in the temperature graph while still achieving the desired decluttering for precipitation and cloud cover.

## Objective
-   **Introduce Immovable Anchors**: Update `GraphLabelPlacementUtils.filterDenseLabelCandidates` to accept an `immovableIndices` parameter.
-   **Protect Essential Markers**: Update `TemperatureGraphRenderer` to treat `explicitAnchors` (like START, END, ACTUAL_HIGH) as immovable.
-   **Maintain Decluttering**: Ensure Precipitation and Cloud Cover renderers still allow soft dips to prune each other.

## Key Files & Context
-   `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Core filtering logic.
-   `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Uses soft dips.
-   `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Uses soft dips.
-   `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Has essential markers that must not be pruned.

## Implementation Steps

### 1. Update `GraphLabelPlacementUtils`
Modify `filterDenseLabelCandidates` to:
- Accept `immovableIndices: Set<Int> = emptySet()`.
- Use this set (plus global max/min) to build the internal `immovableAnchors` set.
- Ensure only `immovableAnchors` are excluded from the pruning process.

### 2. Update `TemperatureGraphRenderer`
Pass `explicitAnchors` to the `immovableIndices` parameter. This prevents `END` from being pruned by a nearby `LOW` or `HIGH`, and `ACTUAL_HIGH` from being pruned by a nearby `HIGH` (forecast).

### 3. Update `PrecipitationGraphRenderer` and `CloudCoverGraphRenderer`
Continue to pass `softDipCandidates` as `protectedIndices` (not immovable). This allows redundant dips (e.g., "9%, 9%") to be pruned to a single label.

### 4. Verification
-   **Run Repro Test**: `PrecipitationClutterReproTestV2.kt` (verify 29%/29% and 10%/9% are decluttered).
-   **Run Temperature Tests**: `TemperatureGraphLabelPlacementRobolectricTest.kt` (verify END and ACTUAL_HIGH are back).

## Verification & Testing

### Automated Tests
-   `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.PrecipitationClutterReproTestV2`
-   `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest`
-   `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.PrecipitationGraphRendererTest`
-   `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.CloudCoverGraphRendererTest`

### Manual Verification
-   Confirm visual layout in emulator for mixed rain/cloud scenarios.
