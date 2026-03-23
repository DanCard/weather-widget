# Plan: Fix Misleading "Job was Cancelled" Logs

Refine exception handling across repositories and workers to distinguish between genuine failures and intentional coroutine cancellations. This will eliminate "false positive" error logs in the database (`app_logs`) that often occur on Samsung devices during rapid UI interactions or overlapping sync schedules.

## Objective
- Eliminate misleading `ERROR` and `WARN` logs in `app_logs` when a job is intentionally cancelled.
- Ensure `CancellationException` is properly rethrown to maintain structured concurrency.
- Log cancellations as `INFO` or `DEBUG` to provide clear but non-alarming diagnostics.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`**: Current `runCatching` usage in `fetchStationObservation` catches and logs cancellations as `NWS_STATION_FAIL`.
- **`app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`**: Generic `try-catch` in `refreshCurrentTemperature` logs cancellations as `CURR_FETCH_ERROR`.
- **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`**: `doWork` and `handleCurrentTempOnlyWork` log cancellations as `SYNC_EXCEPTION` or `CURR_FETCH_EXCEPTION`.
- **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`**: `onUpdate` catches cancellations and logs them as `WIDGET_EXCEPTION`.

## Implementation Steps

### 1. Refactor Observation Repository
- In `fetchStationObservation`, replace `runCatching` with a `try-catch` block.
- Explicitly rethrow `CancellationException`.
- Only log `NWS_STATION_FAIL` for non-cancellation exceptions.

### 2. Refactor Current Temp Repository
- In `refreshCurrentTemperature`, update the `forEach` loop and the outer `try-catch` to check for `CancellationException`.
- Use a `when` or `if` check to handle `CancellationException` separately from other errors.

### 3. Update Worker Diagnostics
- In `WeatherWidgetWorker.kt`, update `doWork` and `handleCurrentTempOnlyWork`.
- If a `CancellationException` occurs, log it with a new tag `SYNC_CANCELLED` or `CURR_FETCH_CANCELLED` at `INFO` level.
- Ensure the worker returns `Result.retry()` or `Result.failure()` appropriately (usually `Result.retry()` if cancelled by system, but `Result.success()` might be better if manually replaced).

### 4. Update Provider Lifecycle
- In `WeatherWidgetProvider.kt`, update the `onUpdate` catch block to ignore `CancellationException` or log it as a verbose lifecycle event.

## Verification & Testing
- **Log Verification**: Perform rapid widget resizing and manual refreshes on an emulator. Check `app_logs` to ensure no `ERROR` level logs appear with "Job was cancelled".
- **SQL Audit**: Run `SELECT * FROM app_logs WHERE message LIKE '%cancelled%'` to verify new `INFO` level cancellation tags are appearing correctly.
- **Structured Concurrency**: Ensure that cancellations still properly propagate and stop execution as expected.
