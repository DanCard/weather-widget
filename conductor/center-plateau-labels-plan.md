# Plan: Center Hourly Temperature Labels Over Plateaus

The temperature graph currently labels local extrema and forecast lows at the first point of a plateau (sequence of identical values). This plan updates the labeling logic to center these labels horizontally over the entire plateau and ensures they are correctly identified as peaks or valleys for proper vertical placement.

## Objective
- Center `FORECAST_LOW` and `LOCAL` extremum labels horizontally over plateaus.
- Improve `isValley` and `isPeak` detection to handle plateaus, ensuring labels are placed on the correct side of the line (below for dips, above for peaks).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Contains the label placement loop and the `centerOfRun` utility.

## Implementation Steps

### 1. Expand `centerOfRun` Usage
In `TemperatureGraphRenderer.kt`, update the `sx` calculation to include `FORECAST_LOW` and `LOCAL` roles.

```kotlin
// TemperatureGraphRenderer.kt around line 797
val sx = if (candidate.role in listOf("LOW", "HIGH", "FORECAST_LOW", "FORECAST_HIGH", "LOCAL")) {
    centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX).first
} else points[idx].first
```

### 2. Improve Peak/Valley Detection for Plateaus
Update `isValley` and `isPeak` logic in the label placement loop to look past identical neighbors. This ensures that even if a label is centered over a plateau, the algorithm knows whether to place it above or below the curve.

```kotlin
// TemperatureGraphRenderer.kt around line 813
val leftVal = temps.subList(0, idx).findLast { it != temps[idx] } ?: temps[idx]
val rightVal = temps.subList(idx + 1, temps.size).find { it != temps[idx] } ?: temps[idx]
val isValley = candidate.role == "LOW" || candidate.role == "FORECAST_LOW" || (candidate.role == "LOCAL" && temps[idx] < leftVal && temps[idx] < rightVal)
val isPeak = candidate.role == "HIGH" || candidate.role == "FORECAST_HIGH" || (candidate.role == "LOCAL" && temps[idx] > leftVal && temps[idx] > rightVal)
```

### 3. Verification & Testing
- **Move Test File**: After exiting Plan Mode, move `conductor/TemperatureGraphPlateauCenteringTest.kt` to `app/src/test/java/com/weatherwidget/widget/TemperatureGraphPlateauCenteringTest.kt`.
- **Run Tests**: Execute the new tests to verify centering for `FORECAST_LOW` and `LOCAL` extrema.
- **On-Device Verification**: Observe the hourly graph on an emulator/device with NWS data (which often has integer plateaus) to ensure labels appear centered over flat dips/peaks.
