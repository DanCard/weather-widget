# Plan: Advanced Label Decluttering with Prunable Boundaries

Address visual clutter in hourly precipitation and cloud cover graphs where boundary labels (START/END) are redundant with nearby more significant labels (Global Max/Min), specifically cases like "43% and 42%".

## Objective
- **Make Boundaries Prunable**: Refine `GraphLabelPlacementUtils.filterDenseLabelCandidates` to ensure that while Global Max and Global Min remain immovable, other candidates like START (index 0) and END (last index) can be pruned if they are too close to a higher-priority anchor.
- **Maintain High Priority for Boundaries**: Ensure START and END labels still have a relatively high priority (Priority 4) so they only get pruned by more significant anchors (Global Max/Min or soft dips) when redundant.
- **Increase Proximity Window**: Increase the `nearbyWindow` from 4 to 5 for Precipitation and Cloud Cover graphs to improve decluttering on high-resolution devices like the Pixel 7 Pro.
- **Unify Cloud Cover Thresholds**: Update Cloud Cover's `DENSE_LABEL_DIFF_THRESHOLDS` to match Precipitation (starting with 5%) to catch 1% differences.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Core filtering logic.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Precip rendering.
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Cloud rendering.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Temperature rendering (protects its own boundaries).

## Implementation Steps

### 1. Update `GraphLabelPlacementUtils.kt`
- Keep `globalMaxIdx` and `globalMinIdx` in `immovableAnchors`.
- Ensure the `optionalCandidates` list (those eligible for removal) includes all other indices, specifically including index 0 and `lastIndex` if they are not the Global Max/Min.
- (Self-Correction: My previous implementation already did this! The issue was that in Scenario 1, index 0 *was* the Global Min, so it was immovable. If index 24 (END) is also 42%, it is NOT the Global Min, so it IS currently prunable. I need to verify why it stayed.)

### 2. Update `PrecipitationGraphRenderer.kt` and `CloudCoverGraphRenderer.kt`
- Increase `nearbyWindow` to 5.
- (Verification of Scenario 1): START=0(42%)[GMIN], MAX=10(43%)[GMAX], END=24(42%).
  - With `nearbyWindow=4`: END(24) is 14 hours away from MAX(10). No pruning.
  - With `nearbyWindow=5`: END(24) is still 14 hours away.
  - Wait, if the user sees "43% and 42% around 7pm", they must be close.
  - If 7pm is index 19, then 43% might be at 19 and 42% at 24 (END). Distance = 5.
  - If `nearbyWindow` was 4, they wouldn't prune. Increasing to 5 would prune END!

### 3. Update `CloudCoverGraphRenderer.kt`
- Update `DENSE_LABEL_DIFF_THRESHOLDS` to `listOf(5, 10, 15)`.

### 4. Verification
- **Run Repro Test**: `PrecipitationClutterReproTestV3.kt` (update Scenario 1 to put 43% and 42% within 5 hours).
- **Run Temperature Tests**: `TemperatureGraphLabelPlacementRobolectricTest.kt`.

## Verification & Testing

### Automated Tests
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.PrecipitationClutterReproTestV3`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.PrecipitationGraphRendererTest`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.CloudCoverGraphRendererTest`
