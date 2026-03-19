# Plan: Fix Samsung Sluggishness and Sync Bottlenecks

The goal is to eliminate the severe sluggishness and 80-second sync times reported on Samsung devices by optimizing database queries, consolidating scheduling, and implementing a job-tracking system.

## Background & Motivation
Recent logs from a Samsung device show:
1. **Parallel Sync Jobs**: Four `WeatherWidgetWorker` instances frequently fire at once (Periodic, OneTime, Opportunistic, ScheduledLoop), competing for CPU and DB locks.
2. **Slow Queries**: `getLatestForecastsInRange` takes 24 seconds due to a correlated subquery and missing index columns. `getForecastsInRange` loads thousands of stale rows instead of only the latest batch.
3. **UI Blocking**: The click handler for the widget takes up to 7 seconds to respond when background syncs are running, causing a poor user experience.
4. **Redundant Work**: Every small job (CurrentTemp, Backfill) performs a full `refreshWidgetsFromCache()`, which re-queries the entire database.

## Proposed Changes

### 1. Database Query Optimization
- **`ForecastDao.kt`**:
    - Update the index used by `getLatestForecastsInRange`. Modify the `forecasts` table index to include `targetDate` as the first column: `(targetDate, source, locationLat, locationLon, batchFetchedAt)`.
    - Update `getForecastsInRange` in `ForecastDao.kt` to use the same logic as `getLatestForecastsInRange` (only returning the most recent batch per date/source) to avoid loading thousands of stale rows.
- **`WeatherDatabase.kt`**:
    - Explicitly enable WAL mode using `.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)` in the `Room.databaseBuilder`.

### 2. Consolidated Scheduling
- **`WeatherWidgetWorker.kt`**:
    - Remove the `scheduleNextUpdate()` manual loop (which uses `OneTimeWorkRequest`).
    - Rely on `PeriodicWorkRequest` (1 hour) plus opportunistic triggers.
    - Add a "cooldown" check to `doWork()` to skip execution if a successful full sync occurred within the last 5 minutes.

### 3. Widget Update Tracking & Debouncing
- **`WidgetUpdateTracker.kt`**: Implement the job tracking system from the original Samsung fix plan to cancel redundant update coroutines.
- **`WidgetIntentRouter.kt`**: Add 250ms debouncing to `handleResize` to handle Samsung Foldable screen transitions gracefully.

### 4. Efficient Cache Refreshes
- **`WeatherWidgetWorker.kt`**:
    - Optimize `refreshWidgetsFromCache()` to only fetch the minimum necessary data.
    - Pass a `skipFullRefresh` flag to `getWeatherData` when called from small jobs to avoid the double `getCachedData` call.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`: Slow queries.
- `app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt`: Indexes.
- `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`: DB config.
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`: Worker orchestration.
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`: Resize/UI logic.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`: UI queries.

## Implementation Steps

### Phase 1: Database Optimization
1. Modify `ForecastEntity.kt` to optimize the index: `["targetDate", "source", "locationLat", "locationLon", "batchFetchedAt"]`.
2. Update `ForecastDao.kt` queries to prioritize the latest batches.
3. Explicitly enable WAL in `WeatherDatabase.kt`.

### Phase 2: Worker Refactoring
1. Remove `scheduleNextUpdate()` from `WeatherWidgetWorker.kt`.
2. Add sync cooldown logic to `doWork()`.
3. Introduce `WidgetUpdateTracker.kt`.

### Phase 3: UI & Intent Handling
1. Implement resize debouncing in `WidgetIntentRouter.kt`.
2. Refactor `WeatherWidgetProvider.kt` to use the `WidgetUpdateTracker`.

## Verification & Testing
- **Performance Logging**: Monitor `SYNC_PERF` and `CLICK_SLOW` logs in the database. Expect `weather` time to drop from 24s to <1s.
- **Concurrency Test**: Verify through logs that multiple workers no longer fire at the exact same second.
- **Manual Verification**: Test the widget interaction (clicking "Today") on the Samsung device while a sync is running to ensure it responds in <500ms.
