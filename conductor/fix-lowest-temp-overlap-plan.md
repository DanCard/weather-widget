# Fix Lowest Temp Overlap Plan

## Objective
Prevent the lowest temperature label in the daily forecast graph from overlapping with the night rain chance label by forcing the temperature value to format as an integer (dropping any decimals) when space is at its tightest.

## Key Files & Context
*   **`app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`**: Handles the rendering of the daily graph, including the low temperature labels and delegating to the rain label renderer.

## Implementation Steps

1.  **Modify `drawDayBars` in `DailyForecastGraphRenderer.kt`**:
    *   Locate the section where `lowLabelText` is generated (around line 575):
        ```kotlin
        val lowTempY = iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)
        val lowLabelText = formatTempLabel(lowTemp, day.isToday || day.isPast)
        ```
    *   Update this logic to check if the current `lowTemp` is the minimum temperature (`layout.minTemp`) and if there is a night rain chance to display.
    *   If both conditions are met, force `isActualData` to `false` so the temperature is rounded to a 2-digit integer (e.g., `49°` instead of `48.6°`), saving horizontal space.
    *   **Proposed Logic**:
        ```kotlin
        val isLowest = lowTemp <= layout.minTemp + 0.01f
        val hasNightRain = day.rainData.nightRainLabelText != null
        val forceInteger = isLowest && hasNightRain
        val isActualData = (day.isToday || day.isPast) && !forceInteger
        val lowLabelText = formatTempLabel(lowTemp, isActualData)
        ```

## Verification & Testing
*   Verify that if the lowest temperature has a fractional component (e.g., `48.6`) and it falls on a day with a night rain chance, the label renders as an integer (e.g., `49°`).
*   Verify that days that are not the lowest still render with their full fractional precision if they are Today or a Past day.
*   Verify that if the lowest temperature does NOT have a night rain chance, it still renders with full precision (if Today or Past).
*   Run all unit tests (`./gradlew test`) to ensure no regressions in graph rendering or label placement.