# Fix Today's Daily Forecast Icon

**Objective:** Ensure the icon for "Today" in the daily forecast list stays in sync with the main widget icon by using the current hourly condition instead of the stagnant daily summary.

## The Problem
The NWS daily summary for today is a 24-hour summary (e.g., "Patchy Fog then Mostly Sunny"). If "Fog" is the first condition mentioned, the daily icon remains "Fog" all day, even after the fog burns off and the main widget icon (which uses hourly data) updates to "Sunny". This creates a confusing visual discrepancy.

## The Fix
Modified `DailyViewHandler.kt` to:
1.  **Graph Mode (`buildDayDataList`):** When processing "Today", fetch the current hour's condition from `hourlyForecasts` and use it if available.
2.  **Text Mode (`populateDay`):** Updated `populateDay` to accept `isToday`, `hourlyForecasts`, and `displaySource` parameters. If `isToday` is true, it overrides the daily condition with the current hourly condition.

## Verification
- Verified code changes compile.
- Checked logic to ensure it only affects "Today" and respects the selected display source.
