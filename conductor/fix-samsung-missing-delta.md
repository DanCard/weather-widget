# Plan: Fix Missing Temperature Delta in Top-Left Display

This plan addresses the issue where the temperature delta (e.g., "+1.2°") is missing from the top-left current temperature display, particularly observed on Samsung devices but relevant across all platforms.

## Root Cause Analysis
The investigation revealed that `TemperatureViewHandler` gates the visibility of the temperature delta on a UI flag called `isNowLineVisible`. This flag is intended to track whether the "Now" line is visible on the hourly graph, but it is incorrectly used to hide the header's delta as well.

`isNowLineVisible` is false in several common scenarios:
1. **1-Row Mode**: If the widget height is reported as $\le 100dp$ (common on Samsung launchers), `useGraph` is set to false, and `graphHours` is initialized as an empty list. This causes `isNowLineVisible` to be false, hiding the delta even if it exists.
2. **Scrolled View**: If the user has navigated the hourly graph into the future or past such that the current hour is no longer on screen.
3. **Data Gaps**: If the weather source (e.g., NWS) is missing a forecast entry for the exact current hour, `buildHourDataList` will not include an item with `isCurrentHour = true`, even if the header successfully interpolates a value.

Since the top-left header ALWAYS shows the current temperature (independent of the graph's scroll state or visibility), its delta should also be shown independently of `isNowLineVisible`.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`**: Main culprit for the `isNowLineVisible` gating.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`**: Correctly shows the delta but hides it when rain probability is visible to save space.
- **`app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`** & **`CloudCoverViewHandler.kt`**: Currently hide the delta entirely.

## Implementation Steps

### 1. Enhanced Logging (Verification)
- Add temporary debug logging to `TemperatureViewHandler.kt` and `app_logs` to record `heightDp`, `rawRows`, `useGraph`, `isNowLineVisible`, and `appliedDelta` to confirm the scenario on-device.

### 2. Decouple Header Delta from Graph State
- Modify `TemperatureViewHandler.kt` to show the `current_temp_delta` based only on its value (significant if $\ge 0.1^\circ$) and the presence of a current temperature, ignoring `isNowLineVisible`.
- Apply the same change to `applyCurrentTempHeader` to ensure refined updates also show the delta correctly.

### 3. Consistency across View Modes
- Remove the `!isPrecipVisible` restriction in `DailyViewHandler.kt`. Samsung devices (especially foldables) have ample horizontal space to show both the delta and the precipitation percentage.
- Enable the delta display in `PrecipViewHandler.kt` and `CloudCoverViewHandler.kt` for a consistent experience across all widget modes.

## Verification & Testing
- **Log Verification**: Confirm via `adb logcat` or `app_logs` that the delta is being calculated but suppressed by the old logic.
- **1-Row Mode**: Resize the widget to its minimum height and verify the delta appears next to the temperature.
- **Scrolled Mode**: Navigate the hourly graph to a future day and verify the top-left current temp delta remains visible.
- **Rainy Mode**: Verify that both the delta (e.g., "+1.5") and rain probability (e.g., "60%") are visible in the header.
