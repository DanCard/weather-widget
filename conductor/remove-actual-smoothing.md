# Plan: Remove Smoothing from Actual Temperature Line

The user requested to remove smoothing from the actual temperature line in the hourly graph. Currently, the "Truth Curve" (solid actual line + ghost projection) is smoothed using a weighted moving average. This plan removes that data-level smoothing, ensuring the line accurately reflects the raw station observations.

## Objective
Remove the `GraphRenderUtils.smoothValues` step for the actual temperature and precipitation lines while maintaining the cubic spline interpolation for visual fluidness between points.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Logic for the temperature truth curve.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Logic for the precipitation probability curve.

## Implementation Steps

### 1. Update TemperatureGraphRenderer
- In `renderGraph`, remove the `smoothValues` call for `rawTruthTemps`.
- Set `smoothedTruthTemps = rawTruthTemps`.
- This ensures `originalPoints` and the visual path are grounded in the raw data.

### 2. Update PrecipitationGraphRenderer
- In `renderGraph`, remove the `smoothValues` call for `rawProbs`.
- Set `smoothedProbs = rawProbs`.
- This ensures the precipitation line remains sharp and follows exact reported percentages.

### 3. Verification
- **Visual Verification**: Confirm on the emulator that the temperature and precipitation lines now pass exactly through their raw data points (may appear slightly more "jagged" for integer-based station reports).
- **Unit Tests**: Run `TemperatureGraphRendererContinuityTest` and `TemperatureGraphRendererActualsTest` to ensure line grounding remains correct.
