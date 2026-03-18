# Plan: Fix Missing "Today" High in Daily Actuals Bar

## Objective
Address the issue where the "actuals" bar for today in the daily forecast view shows an incorrect (too low) high temperature, often matching the low temperature. This is typically caused by missing historical observations for the current day, especially if the app hasn't been running or just started. We will improve the NWS backfill logic to ensure today's observations are fetched if they are missing.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`: Contains the `backfillNwsObservationsIfNeeded` logic.
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`: Calculates the values for the "3 bars" for today.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Prepares the data for the daily graph renderer.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Renders the triple-line bar for today.

## Proposed Changes

### 1. Enhance NWS Backfill Trigger Logic
In `CurrentTempRepository.kt`, update `backfillNwsObservationsIfNeeded` to check not only yesterday's observations but also today's.
- If it's already several hours into the day (e.g., past 2 AM) and there are no or very few observations for today, trigger the backfill.
- This ensures that when the app starts at 7 AM (as in the emulator case), it fetches the observations from 12 AM to 7 AM, providing a more accurate "high so far" and "low so far".

### 2. Improve Fallback in `DailyActualsEstimator` (Optional but Recommended)
If `observedHigh` is missing but we have a `currentTemperature` from a very recent fetch, we should at least use that. However, `backfill` is the more robust solution for the "3 bars" view which expects history.

## Implementation Steps

1.  **Modify `CurrentTempRepository.kt`**:
    - Update `backfillNwsObservationsIfNeeded` to include a check for today's observations.
    - Define a "completeness" threshold for today based on the current hour.
    - If either yesterday or today is incomplete, proceed with the NWS API fetch.

2.  **Create `NwsBackfillTest.kt`**:
    - Add unit tests to verify that `backfillNwsObservationsIfNeeded` correctly triggers when yesterday is complete but today is missing data (e.g., at 8 AM with 0 points).
    - Verify that it DOES NOT trigger if both yesterday and today have sufficient data.

3.  **Verify `ObservationResolver.kt`**:
    - Ensure it correctly aggregates the newly fetched observations into `hourly_actuals` and then into `DailyActual` for today.

## Verification & Testing
- **Manual Verification**:
    - Clear app data or delete today's observations from the database.
    - Restart the app/trigger a sync.
    - Verify that `backfillNwsObservationsIfNeeded` is triggered and fetches today's earlier points.
    - Check the daily widget to see if the "actuals" bar (orange/yellow) now shows a realistic range for today instead of just a single point at the bottom.
- **Unit Tests**:
    - Run `NwsBackfillTest.kt` to verify the logic in isolation.
    - Verify `WeatherRepositoryTest.kt` still passes.
