# Plan: Fix Dropping Daily High Temperature

The daily high temperature in the "Today" column sometimes drops. This is likely because the current observed high isn't correctly persisted or the aggregation logic (using sliding 24h windows from NWS) causes the "high" to decrease as a previous day's peak falls out of the window.

## Objective
Ensure the observed daily high for "Today" is persistent and only increases (never drops) as new observations are received.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`: Aggregates observations into daily extremes.
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`: Manages the lifecycle and persistence of daily extremes.
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`: Calculates values for the triple-bar rendering for "Today".

## Proposed Solution

### 1. Update Aggregation Logic
Modify `ObservationResolver.blendExtremes` to ensure the daily high is the maximum of the official 24h extremes AND all spot observations for that day. This prevents a sliding 24h window (from NWS) from causing the "today" high to drop when yesterday's peak falls out of the 24h range.

### 2. Protect Persistence
Modify `ObservationRepository` to prevent overwriting an existing daily high with a lower value for the same day/source/location.

### 3. Add DB Logging
Add detailed logging to the `app_logs` table during the aggregation and persistence phase to track how the daily high is being calculated and updated.

## Implementation Steps

### 1. ObservationResolver.kt
- In `blendExtremes`, ensure the final `high` is `max(calculated_idw_high, max_spot_of_all_obs)`.
- In `blendExtremes`, ensure the final `low` is `min(calculated_idw_low, min_spot_of_all_obs)`.

### 2. ObservationRepository.kt
- Modify `recomputeDailyExtremesForDay` (both overloads) and `recomputeDailyExtremesFromStoredObservations` to:
    1. Fetch existing `DailyExtremeEntity` for the target day/source.
    2. Compare new values with existing values.
    3. Only update if the new high is higher or the new low is lower (or if no existing record exists).
- Add `appLogDao.log` calls to record:
    - Current stored high/low.
    - New calculated high/low.
    - Whether an update was performed or skipped.

### 3. DailyActualsEstimator.kt
- Verify `calculateTodayTripleLineValues` correctly uses the persisted `actual.highTemp`.
- It currently uses `val observedHigh = currentTemp ?: actual?.highTemp`. If `actual?.highTemp` is the true persistent high, this might still drop if `currentTemp` (the latest reading) drops.
- **Change**: Ensure `observedHigh` in the triple-line represents the *max so far today*, which should be `max(currentTemp, actual?.highTemp)`.

## Verification & Testing
- **Unit Tests**: Add a test case to `ObservationResolverTest` verifying that `blendExtremes` returns the `maxSpot` even if `maxTempLast24h` is lower.
- **Integration Tests**: Verify in `ObservationRepository` that inserting a lower observation after a higher one does not decrease the `daily_extremes` high.
- **Manual Verification**: Observe logs in the app's "Logs" view to see "Daily extreme updated: high 80 -> 82" or "Daily extreme update skipped: 82 > 79".

