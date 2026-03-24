# Plan - Remove ".0" from Temperature Labels in Hourly Graph

Eliminate the unnecessary ".0" decimal part from temperature labels on the hourly graph when the value is a whole number (e.g., "72.0°" becomes "72°", but "72.1°" remains "72.1°").

## Objective
The current hourly temperature graph displays all values with one decimal place. This plan updates the formatting to omit the decimal part when it is zero, resulting in a cleaner and more readable display.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**: Contains the label formatting logic.
- **`app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`**: Unit tests that check label text.
- **`app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererStalenessTest.kt`**: Unit tests that check label text.
- **`app/src/test/java/com/weatherwidget/widget/TruthCurveLinearRenderingTest.kt`**: Unit tests that check label text.
- **`app/src/androidTest/java/com/weatherwidget/widget/TemperatureFetchDotIntegrationTest.kt`**: Instrumented tests that check label text.

## Implementation Steps

### 1. Add `formatTemp` Helper in `TemperatureGraphRenderer.kt`
- Define a private helper function:
  ```kotlin
  private fun formatTemp(value: Float): String {
      return if (value % 1f == 0f) {
          String.format("%.0f", value)
      } else {
          String.format("%.1f", value)
      }
  }
  ```
- Or more succinctly: `if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)`

### 2. Apply `formatTemp` in `TemperatureGraphRenderer.kt`
- Update `labelTextFor(temps: List<Float>, index: Int)` to use `formatTemp(temps[index])`.
- Update the main label loop: `val label = formatTemp(labelTemps[idx]) + "°"`.
- Update the "Last Fetch Dot" value label: `val valueLabel = formatTemp(resolvedFetchTemp) + "°"`.
- Update `LOCAL` and `ACTUAL_END` candidate logic where `labelText` is created.

### 3. Update Affected Tests
- **`TemperatureGraphLabelPlacementRobolectricTest.kt`**: Update `81.0` to `81`, etc.
- **`TemperatureGraphRendererStalenessTest.kt`**: Update `60.0°` to `60°`.
- **`TruthCurveLinearRenderingTest.kt`**: Update `73.3°` if it happened to be a whole number (though 73.3 is fine).
- **`TemperatureFetchDotIntegrationTest.kt`**: Update `65.0°` to `65°`.

## Verification & Testing

### Manual Verification
- Deploy to an emulator or device.
- Observe the hourly temperature graph.
- Verify that whole-number temperatures (e.g., 72.0) are displayed without the decimal (e.g., 72°).
- Verify that temperatures with non-zero decimals (e.g., 72.1) still show the decimal (e.g., 72.1°).

### Automated Testing
- Run all unit and emulator tests to ensure the updated assertions pass and no regressions were introduced.
