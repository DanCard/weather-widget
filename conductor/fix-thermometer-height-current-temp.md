# Plan - Thermometer Height Represents Current Temperature

## Objective
Adjust the "Today" column's observed temperature bar (the solid red "thermometer") so that its top strictly represents the current temperature. If a higher temperature was reached earlier in the day, it will be visualized using the semi-transparent "ghost bar" above the thermometer.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`: Logic that calculates the "observed" range for the Today column.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Maps estimator values to UI data models.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Renders the bars and labels (already supports ghost bars and absolute-high labels).

## Implementation Steps

### 1. Update `DailyActualsEstimator.kt`
Modify `calculateTodayTripleLineValues` to change how `observedHigh` is determined.
- **Change:** Instead of `observedHigh = maxOf(actual?.highTemp, currentTemp)`, set `observedHigh = currentTemp ?: actual?.highTemp`.
- **Rationale:** This makes the "top of the mercury" follow the current temperature. If the temperature drops, the solid red bar drops with it. The `trueActualHigh` field (already populated from `actual?.highTemp`) will preserve the peak for the ghost bar logic.

### 2. Update `DailyViewLogic.kt`
Ensure the text-mode high label for the Today column remains the absolute high (peak reached or predicted), even if the thermometer level drops.
- **File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- **Method:** `prepareTextDays`
- **Change:** Update the `visibleHigh` calculation for the `isToday` case.
- **Logic:** Use `listOfNotNull(tripleValues.observedHigh, tripleValues.forecastHigh, tripleValues.trueActualHigh).maxOrNull()` instead of just `tripleValues.observedHigh`.

### 3. Verification & Testing
- **Unit Tests**:
    - Update `app/src/test/java/com/weatherwidget/util/DailyActualsEstimatorTest.kt` to verify that `observedHigh` correctly follows `currentTemp` even when it is lower than a previous peak.
    - Add a test case to `DailyViewLogicTest.kt` (if it exists) or verify via `DailyViewHandlerTest.kt` that the text label for Today remains at the absolute peak.
- **Manual Verification**:
    - Use a device or emulator.
    - Observe the "Today" column in the daily graph. 
    - Verify that the solid red bar matches the large temperature number shown in the widget header.
    - Verify that if the temperature is below the day's high, a "ghost bar" appears above the red bar.
    - Verify that the high temperature label (printed above the bar) still shows the daily high, not the current temperature.

## Migration & Rollback
- This is a visual-only change in the rendering logic and does not affect the database schema.
- Rollback is achieved by reverting the logic in `DailyActualsEstimator.kt`.
