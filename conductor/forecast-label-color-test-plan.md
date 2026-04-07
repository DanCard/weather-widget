# Test Plan: Forecast Label Coloring in History

Verify that the hourly temperature graph correctly colors labels based on their data source (forecast vs. actual) rather than their horizontal position.

## Objective
- Ensure `HIGH`, `LOW`, and `LOCAL` landmark labels are always marked as `series="forecast"` (blue) even when they appear in the history section.
- Ensure `ACTUAL_HIGH` and `ACTUAL_LOW` labels are always marked as `series="actual"` (yellow).
- Ensure `START` and `END` labels use the correct series based on whether they land on an observed or forecast point.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Rendering logic.
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`: Unit test suite.

## Test Cases

### 1. Unified Series Verification (Robolectric)
- **Scenario**: Create a graph where the global `LOW` (forecasted as 51°) occurs in the past (index 5) and an `ACTUAL_LOW` (observed as 50.4°) occurs nearby (index 7).
- **Assertions**:
  - `LOW` label (idx 5, 51°) must have `series == "forecast"`.
  - `ACTUAL_LOW` label (idx 7, 50.4°) must have `series == "actual"`.
  - Both labels should be present (though potentially stacked or displaced by collision detection).

### 2. Peak Role Verification (Robolectric)
- **Scenario**: A `HIGH` role (72°) occurs in the past and a `LOCAL` extremum (68°) also occurs in the past.
- **Assertions**:
  - `HIGH` label must have `series == "forecast"`.
  - `LOCAL` label must have `series == "forecast"`.

### 3. Transition Point Verification (Robolectric)
- **Scenario**: The `END` of the graph lands in the future.
- **Assertions**:
  - `END` label must have `series == "forecast"`.

### 4. Start Point Verification (Robolectric)
- **Scenario**: The `START` of the graph lands in the past.
- **Assertions**:
  - `START` label must have `series == "actual"`.

## Implementation
Add a new test method `testForecastLabelsInHistoryAreColoredBlue` to `TemperatureGraphLabelPlacementRobolectricTest.kt` covering the above scenarios in a single comprehensive graph render.
