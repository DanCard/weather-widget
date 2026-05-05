# Fix Pixel Slow Startup & CursorWindow Crash

## Objective
Resolve the slow widget startup and `IllegalStateException` (CursorWindow size limit exceeded) on Pixel devices. 

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`: Contains `fetchForecastSnapshots` which currently attempts to load a massive 44-day window (30 days past, 14 days future) of all historical forecast snapshots.

## Implementation Steps
1. Modify `WeatherWidgetWorker.kt` -> `fetchForecastSnapshots`:
   - Change `startDate` calculation from `minusDays(30)` to `minusDays(1)` (yesterday).
   - Change `endDate` calculation from `plusDays(14)` to `plusDays(7)` (next 7 days).
   - This reduces the data load from 44 days to 8 days, keeping the `CursorWindow` well below its 2MB limit and ensuring rapid widget startups.

## Verification & Testing
- Compile and install the app.
- Check `logcat` to verify that `WeatherWidgetWorker` completes successfully without the `CursorWindow` crash.
- Ensure the widget UI updates properly and shows historical background data for the previous day.