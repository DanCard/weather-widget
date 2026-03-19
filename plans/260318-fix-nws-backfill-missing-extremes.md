# Fix NWS Backfill: Populating Daily Extremes for History

## Objective
Address the issue where "Yesterday" and "Today" extreme temperatures (high/low) are missing from the daily forecast history, even when observations are available or have been backfilled. The root cause is that the NWS backfill logic only populates the `daily_extremes` table (used by the UI) when "official" 24h extreme values are found in the observation metadata. If the station does not provide these values, the backfill is considered failed even if raw hourly observations are successfully fetched.

## Background & Motivation
- **`ObservationRepository.backfillNwsObservationsIfNeeded`** is responsible for fetching historical data for the NWS source.
- It triggers when `daily_extremes` are missing for "Yesterday" (or "Today" if late in the day).
- It fetches 48 hours of observations and inserts them into the `observations` table.
- However, it only populates the `daily_extremes` table if `ObservationResolver.officialExtremesToDailyEntities` returns official records (which require `maxTempLast24h` and `minTempLast24h` fields in the raw JSON).
- Many NWS stations (especially personal ones or certain official ones at specific hours) do not provide these fields.
- The daily forecast UI (`DailyViewHandler` via `WidgetIntentRouter.getDailyActuals`) only queries the `daily_extremes` table for historical actuals.

## Proposed Solution
Update `ObservationRepository.backfillNwsObservationsIfNeeded` to synthesize "calculated" daily extremes from the raw observations if the official ones are missing. This ensures that the `daily_extremes` table is populated and the UI can show history even when the NWS doesn't provide official daily summaries.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`: The location of the backfill logic.
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`: Contains the `aggregateObservationsToDailyBySource` synthesis logic.
- `app/src/main/java/com/weatherwidget/data/local/DailyExtremeEntity.kt`: The target entity, currently marked as "official only".

## Implementation Plan

### 1. Update `ObservationRepository.kt`
Modify `backfillNwsObservationsIfNeeded` to perform a fallback synthesis after the observation fetch loop.
- After attempting to insert official extremes, check if any `remainingDates` still exist.
- If `remainingDates` are still missing, use `ObservationResolver.aggregateObservationsToDailyBySource` on the successfully fetched entities to generate synthetic extremes.
- Filter the synthetic extremes to only include the still-missing `remainingDates` and insert them into `dailyExtremeDao`.

### 2. Update `DailyExtremeEntity.kt`
Update the class documentation to reflect that rows *may* be synthesized from spot observations as a fallback when official data is unavailable.

### 3. Update `ObservationResolver.kt` (Refinement)
Ensure `aggregateObservationsToDailyBySource` handles cases where only partial data is available correctly (e.g., if a station *does* provide `maxTempLast24h` for one hour but not others, it should be prioritized).

## Implementation Steps

### Phase 1: `ObservationRepository` Enhancement
1.  **Modify `backfillNwsObservationsIfNeeded`**:
    -   Maintain a list of all successfully fetched `ObservationEntity` objects during the loop across stations.
    -   After the loop, if `remainingDates` is still not empty:
        -   Call `ObservationResolver.aggregateObservationsToDailyBySource(allFetchedEntities)`.
        -   Extract the NWS entries for the `remainingDates`.
        -   Map them to `DailyExtremeEntity` and insert.
        -   Log the synthesis (e.g., `NWS_STATION_SYNTHESIS` in `app_logs`).

### Phase 2: Code Refinement & Documentation
1.  **Update `DailyExtremeEntity.kt`**:
    -   Revise the Javadoc comment to allow for synthetic fallback.
2.  **Verify `ObservationResolver.kt`**:
    -   Check that `aggregateObservationsToDailyBySource` uses `maxTempLast24h` correctly when available.

## Verification & Testing
- **Unit Tests**:
    -   Create a unit test in `ObservationRepositoryTest` (or similar) where `nwsApi.getObservations` returns observations *without* official extreme fields.
    -   Verify that `backfillNwsObservationsIfNeeded` still populates `dailyExtremeDao` with the correctly synthesized values.
- **Manual Verification**:
    -   Wipe app data and trigger a sync.
    -   Verify in the daily widget that "Yesterday" is populated for NWS even if the nearest station is not an airport (which might lack official CLI reports).
    -   Use `sqlite3` to verify that `daily_extremes` now contains the expected rows.
