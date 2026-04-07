# Plan: Refine Label Pruning for Precipitation and Cloud Cover (v3)

Address visual clutter in hourly precipitation and cloud cover graphs where Global Max and Global Min are very close in value (e.g., 43% and 42%) or when START/END labels are redundant.

## Objective
- **Refine Immovable Anchors**: Update `GraphLabelPlacementUtils.filterDenseLabelCandidates` to only include `globalMaxIdx` (and the provided `immovableIndices`) in the `immovableAnchors` set. The `globalMinIdx` will remain a high-priority candidate (Priority 2) but will no longer be immovable. This allows it to be pruned if it's too close to the Global Max or another immovable anchor.
- **Explicit Temperature Protection**: Ensure `TemperatureGraphRenderer.kt` explicitly protects its `dailyLowIndex` by passing it in the `immovableIndices` set.
- **Increase Proximity Window**: Increase the `nearbyWindow` from 4 to 5 for Precipitation and Cloud Cover graphs to improve decluttering on high-resolution devices like the Pixel 7 Pro.
- **Unify Cloud Cover Thresholds**: Update Cloud Cover's `DENSE_LABEL_DIFF_THRESHOLDS` to match Precipitation (starting with 5%) to ensure the mandatory decluttering pass catches 1% differences.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Core filtering logic.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Precip rendering logic.
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Cloud cover rendering logic.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Temperature rendering logic.

## Implementation Steps

### 1. Update `GraphLabelPlacementUtils.kt`
Modify `filterDenseLabelCandidates` to remove `globalMinIdx` from the default `immovableAnchors` set.

### 2. Update `TemperatureGraphRenderer.kt`
Ensure `dailyLowIndex` is added to the `immovableIndices` set passed to `filterDenseLabelCandidates`.
(Check: `explicitAnchors` contains it if `dailyLowIndex` matches one of the roles. It should.)

### 3. Update `PrecipitationGraphRenderer.kt` and `CloudCoverGraphRenderer.kt`
- Pass `nearbyWindow = 5` to `filterDenseLabelCandidates`.
- Update `DENSE_LABEL_DIFF_THRESHOLDS` in `CloudCoverGraphRenderer.kt` to `listOf(5, 10, 15)`.

### 4. Verification
- **Run Repro Test**: `PrecipitationClutterReproTestV3.kt` (verify 43%/42% and 10%/9% are decluttered).
- **Run Temperature Tests**: `TemperatureGraphLabelPlacementRobolectricTest.kt` (verify LOW labels are still preserved).
- **Regression Tests**: `PrecipitationGraphRendererTest.kt`, `CloudCoverGraphRendererTest.kt`.

## Verification & Testing

### Automated Tests
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.PrecipitationClutterReproTestV3`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.PrecipitationGraphRendererTest`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.CloudCoverGraphRendererTest`
