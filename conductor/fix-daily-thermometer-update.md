# Fix Daily View Current Day Thermometer Update

## Objective
Fix the issue where the "current day thermometer" (the current temperature indicator in the daily forecast view's "Today" column) does not update when the current temperature changes during opportunistic UI updates (e.g. while charging and the display is on).

## Problem Description
When the device is charging and the screen is on, the widget performs frequent, lightweight UI-only updates. During these updates, the `CurrentTemperatureResolver` correctly interpolates a new current temperature and updates the widget's header. However, `DailyViewHandler.kt` mistakenly passes the original, nullable `observedCurrentTemp` parameter down to the daily graph rendering logic (`DailyViewLogic.prepareGraphDays` and `updateTextMode`) instead of the newly interpolated `currentTemp` value. As a result, the daily graph's "Today" column falls back to querying the last raw database observation, bypassing the interpolation and appearing "stuck".

## Implementation Steps

### 1. Update `DailyViewHandler.kt`
- Locate the `updateWidget` method in `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`.
- When calling `DailyViewLogic.prepareGraphDays` (around line 365), replace `currentTemp = observedCurrentTemp` with `currentTemp = currentTemp`.
- When calling `updateTextMode` (around line 456), replace `currentTemp = observedCurrentTemp` with `currentTemp = currentTemp`.

This ensures that the exact same interpolated temperature shown in the widget's header is used to draw the current temperature dot/line on the daily graph, keeping them perfectly in sync.

## Verification
- Run unit tests for `DailyViewHandler` to ensure no regressions.
- Build and run the app. Use the emulator to simulate charging and screen-on conditions.
- Observe the widget to confirm that both the header current temperature and the daily graph's current temperature indicator update simultaneously as time advances.