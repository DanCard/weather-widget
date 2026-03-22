# Plan: Remove Smoothing for Current Temp Dot Calculation

The user reported that the "Last Fetch Dot" on the hourly temperature graph shows a value (76.3) that is synthetically shifted from the raw station observation (75.7) due to smoothing and cubic spline interpolation. This plan removes the smoothing from the dot's temperature and position calculation while keeping the visual curve smooth.

## Objective
Ensure the "Last Fetch Dot" and its numeric label reflect the raw station observation (temporally interpolated but not smoothed) across both Temperature and Precipitation hourly graphs.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Logic for temperature dot.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Logic for precipitation dot.

## Implementation Steps

### 1. Update TemperatureGraphRenderer
Modify `renderGraph` to calculate `interpolatedTruthAtFetch` using `rawTruthTemps` instead of `smoothedTruthTemps`.
- Calculate tangents based on `rawTruthTemps`.
- Use `evaluateCubicY` on the raw series.
- This ensures the dot and label accurately reflect the raw data trend at the specific sub-hourly timestamp.

### 2. Update PrecipitationGraphRenderer
Modify `renderGraph` to calculate `interpolatedProb` using the raw probability list.
- Use `hours[fetchIdx].precipProbability` instead of `smoothedProbs[fetchIdx]` for the linear interpolation.

### 3. Verification
- **Unit Tests**: Run `TemperatureGraphRendererFetchDotTest` and `TemperatureGraphRendererWapiTest` to ensure the dot still aligns with expectations.
- **Log Audit**: Verify that the rendered label matches the `observedTemp` logged by `CurrentTempResolver` (allowing for sub-hourly forecast-trend extrapolation).
- **Visual Check**: Confirm the dot is anchored to the "Raw" reality, potentially appearing slightly off the visual smoothed line if the smoothing was significant.
