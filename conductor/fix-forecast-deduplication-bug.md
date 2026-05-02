# Plan: Fix Forecast Deduplication Logic (Missing Saturday Data)

## Objective
Fix a bug where the daily forecast view shows missing data (e.g., Saturday high) because the deduplication logic incorrectly skips saving recovered data by comparing it against stale, non-latest snapshots.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`: Contains the `saveForecastSnapshot` function where the deduplication logic resides.
- `app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`: Provides the `getForecastsInRangeBySource` query used for comparison.

## Analysis
The investigation of the Samsung device logs and database revealed:
1. NWS API occasionally returns a "regressed" batch where some future dates have missing high/low temperatures (e.g., May 9 having only a Low).
2. When a subsequent "good" fetch occurs, the deduplication logic in `ForecastRepository` should save the new data as an "upgrade" or a new snapshot.
3. However, `getForecastsInRangeBySource` returns rows ordered by `batchFetchedAt DESC` (latest first).
4. `ForecastRepository` uses `associateBy { it.targetDate }` to build a map of "existing" data. Kotlin's `associateBy` picks the **last** occurrence in the list for each key.
5. This means the deduplication logic was comparing new data against the **oldest** stored batch for that date, not the latest.
6. If the new "good" data matches an old "good" batch, it is skipped, leaving the "middle" regressed batch as the latest in the DB for the UI to pick up.

## Implementation Steps
1. Modify `ForecastRepository.kt`:
   - Update the creation of `latestByDate` map to use `distinctBy { it.targetDate }` before `associateBy`. This ensures the **first** (latest) row for each date is used for comparison.
   - Or alternatively, reverse the list before `associateBy`. `distinctBy` is more idiomatic for picking the first occurrence.

```kotlin
// In ForecastRepository.kt
val latestByDate = existingForecasts.distinctBy { it.targetDate }.associateBy { it.targetDate }
```

2. Verification:
   - Run the reproduction test `ForecastDeduplicationBugReproTest` (to be added to `app/src/test/...`).
   - Verify that the test fails before the fix and passes after.

## Verification & Testing
- **Reproduction Test**: Add `app/src/test/java/com/weatherwidget/data/repository/ForecastDeduplicationBugReproTest.kt` with a scenario that simulates a good -> regressed -> good sequence of fetches.
- **Manual Verification**: After deployment, hitting "Refresh Data" on a device with missing columns should now correctly fill them in if the API provides the data.
