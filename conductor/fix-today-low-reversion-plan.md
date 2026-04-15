# Plan: Fix Today's Low Temperature Reversion

## Objective
Fix the issue where "Today's" low temperature on the daily graph occasionally reverts to a higher value (the current temperature) after a few seconds. This is caused by `ObservationRepository` ignoring the persistent `daily_extremes` cache for the current day and relying solely on a live re-computation from raw `observations`. If the specific observation containing the official 24-hour extreme is cleaned up or filtered out, the re-computation fails to find the true low.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`**: Contains `getDailyActualsWithLiveToday`, which currently computes today's extremes live without consulting the persistent cache.
- **`app/src/main/java/com/weatherwidget/data/local/DailyExtremeDao.kt`**: Provides access to the `daily_extremes` table, which has a "persistence guard" (only updates if a new extreme is found).
- **`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`**: Helper for mapping between database entities and UI data models.

## Implementation Steps

### 1. Update `ObservationRepository.getDailyActualsWithLiveToday`
Modify the function to:
- Fetch existing records from the `daily_extremes` table for the current date.
- Merge these persistent records with the live-computed data from the `observations` table.
- Use `minOf` for low temperatures and `maxOf` for high temperatures to ensure the widest recorded range is preserved.

### 2. Enhanced Logging
Add logging to `getDailyActualsWithLiveToday` to record:
- The live-computed low/high for today.
- The cached (persistent) low/high for today.
- The final merged result.
- This will allow definitive verification on the Samsung device.

### 3. Refactor Merge Logic (Optional but recommended)
Ensure the merging of `DailyActualsBySource` maps is robust and handles cases where a source might exist in the cache but not in today's live observations (e.g., if a station goes offline).

## Verification & Testing

### Automated Tests
- **Unit Test**: Create a test case in `ObservationRepositoryTest` (or similar) that simulates:
    1.  A cached `daily_extremes` entry with a low of 46.3.
    2.  Raw `observations` that only show a low of 60.0.
    3.  Verify that `getDailyActualsWithLiveToday` returns 46.3 for that source.

### Manual Verification
- **Log Audit**: On the Samsung device, trigger a refresh and check `adb logcat` for the new `ObservationRepository` logs.
- **Visual Check**: Confirm the daily graph low stays at 46.3 and does not jump to 60+ after a few seconds.
