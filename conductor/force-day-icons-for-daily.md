# Plan: Force 'Day' Icons for Daily Forecast Columns

The "Today" icon currently shows a "Night" version (Moon/Cloud) if viewed before sunrise or after sunset. To make the daily summary more representative of the day's peak weather, we will force the Daily renderer to always use "Day" icons (Sun-based).

## Proposed Changes

### 1. Update `DailyViewLogic.kt`
- Modify the `buildTodayData` and the loop for future days to always pass `isNight = false` to the `WeatherIconMapper.getIconResource` function.
- This ensures that a forecast of "Mostly Cloudy" or "Sunny" always displays with a Sun in the Daily column, even if the current system time is at night.

## Implementation Steps

1.  **Modify `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`**:
    - Locate the `buildTodayData` function.
    - Locate the loop in `prepareGraphDays` that processes future days.
    - Set the `isNight` parameter to `false` in all calls to `WeatherIconMapper.getIconResource`.

## Verification

- Verify on the emulator that the "Today" icon (currently "Mostly Cloudy" at 05:36 AM) now shows a Sun instead of a Moon.
- Verify that other days also use day-time icons.
