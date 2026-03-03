# Objective
Remove rounding for the current day's daily forecast entries in the database (`forecasts` table). This allows for higher precision in current-day accuracy tracking while keeping future days rounded for consistency and storage efficiency.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`: Contains the `saveForecastSnapshot` method which handles the persistence of daily forecasts.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Used for rendering labels. It already handles today's decimal display via hourly data, but this change ensures the database "fallback" and snapshots also maintain this precision.

# Implementation Steps

1. **Modify `ForecastRepository.kt`**:
   - In `saveForecastSnapshot`, update the mapping logic to distinguish between the "target date" being today versus a future date.
   - Preserve the raw `Float` precision for `targetDate == today`.
   - Continue rounding to integers for all future days.

2. **Verify UI Protection**:
   - Ensure that future days in the text-mode UI (`DailyViewLogic.kt`) are still displayed as integers. Since the database will continue to store rounded values for future days, no changes to the UI code are strictly necessary to maintain the current visual appearance.

# Verification & Testing

1. **Unit Test**:
   - Add a test case to `app/src/test/java/com/weatherwidget/data/repository/ForecastSnapshotDeduplicationTest.kt` (or create a new specialized test) to verify that a decimal value like `72.4` for today is stored exactly, while `72.4` for tomorrow is stored as `72.0`.

2. **Database Audit**:
   - Manually trigger a refresh (if possible in test) and check the `forecasts` table to confirm today's entries have decimal precision from Open-Meteo.
