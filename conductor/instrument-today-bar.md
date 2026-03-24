# Plan - Detailed Instrumentation for Daily Forecast "Today" Bar

The user reported that the "Today" temperature bulb looks too tall on the emulator. Initial logs were missing the "Low" side of the temperature-to-pixel mapping (`lowY`) and the final `bulbRadius`. This instrumentation will provide the exact pixel coordinates and dimensions needed to diagnose the issue.

## Objective
Add high-granularity logging to `DailyForecastGraphRenderer.kt` to capture the final rendering coordinates and sizes for the Today triple-bar and bulb.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Contains the rendering logic for the daily forecast bars.

## Implementation Steps

### 1. Log Layout Constants
Log the calculated scale factors and dimensions at the start of `renderGraph`.

- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Change:** Add a log line after `bulbRadius` is calculated (around line 165) to show `scaleFactor`, `tripleBarWidth`, and `bulbRadius`.

### 2. Expand [TODAY] Bar Logs
Update the existing log line for the Today bar to include all relevant Y-coordinates.

- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Change:** Update the log around line 345 to show:
    - `highY`, `lowY` (raw)
    - `sHighY`, `sLowY` (snapshot raw)
    - `fHighY`, `fLowY` (forecast raw)
    - `effectiveLowY`, `effectiveSLowY`, `effectiveFLowY` (after `minBarHeight` is applied)
    - `minBarHeight` (calculated for the current density)

## Verification & Testing

### Manual Verification
1.  **Emulator**: Run the widget on the emulator.
2.  **Logcat**: Check `adb logcat | grep DailyGraphRenderer` and verify that the new fields are populated.
3.  **Data Analysis**: Verify the `lowY - highY` delta and compare it with the `bulbRadius` to see the overlap.
