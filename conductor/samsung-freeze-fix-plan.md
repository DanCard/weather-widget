# Plan: Fix 10-Second Freeze on Samsung Devices (Foldables)

The goal is to eliminate the 10-second widget freeze reported on Samsung devices, particularly foldables. This freeze is likely caused by a "snowball effect" of redundant, heavy update operations triggered rapidly during device unfolding.

## Background & Motivation
On Samsung Foldable devices, unfolding the device triggers multiple `onAppWidgetOptionsChanged` (resize) events as the screen dimensions transition. Currently, the `WeatherWidgetProvider` launches a new coroutine for every event without canceling previous ones. Each coroutine performs:
1. Multiple database queries (forecasts, observations, climate normals).
2. Complex bitmap rendering (Bezier curves, daily bars).
3. Large `RemoteViews` IPC calls (sending bitmaps to the launcher).

Cumulative resource contention (DB locks, CPU, memory) and IPC bandwidth saturation cause the system to freeze the process or the launcher, eventually leading to ANRs or long delays.

## Proposed Changes

### 1. Update Job Tracking & Cancellation
- **`WidgetUpdateTracker`**: Create a new internal object to manage active update `Job`s per `appWidgetId` using a `ConcurrentHashMap`.
- **`WeatherWidgetProvider`**: 
    - In `onUpdate`, split the multi-widget loop so each widget's update is tracked and can be canceled individually.
    - In `onAppWidgetOptionsChanged`, cancel the existing job for that widget before starting the resize update.

### 2. Resize Debouncing
- **`WidgetIntentRouter`**: Update `handleResize` to include a 250ms debounce delay. If another resize or update event for the same widget arrives during this window, the previous one will be canceled by the tracker, effectively ignoring intermediate "stutter" resizes during unfolding.

### 3. Suspend-Aware Rendering
- **`TemperatureGraphRenderer` & `DailyForecastGraphRenderer`**: 
    - Change `renderGraph` to `suspend` functions.
    - Insert `coroutineContext.ensureActive()` at key calculation loops to abort rendering immediately if the job is canceled.

### 4. Database Concurrency
- **`WeatherDatabase`**: Explicitly enable Write-Ahead Logging (WAL) via `.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)` to allow UI reads to proceed even while background syncs are writing to the database.

## Implementation Steps

1. **Create `WidgetUpdateTracker.kt`**: Basic `Job` management logic.
2. **Refactor `WeatherWidgetProvider.kt`**: Integrate tracking into `onUpdate` and `onAppWidgetOptionsChanged`.
3. **Update `WidgetIntentRouter.kt`**: Add `delay(250)` to `handleResize`.
4. **Optimize Renderers**: Add `ensureActive()` to loops in `GraphRenderUtils` and individual graph renderers.
5. **Enable WAL**: Update database builder configuration.

## Verification & Testing
- **Device Logs**: Monitor `adb logcat` for "Job canceled" messages during rapid resizing.
- **Visual Check**: Unfold device and verify the widget updates once after a brief pause, rather than several times in a row.
- **Stress Test**: Hammer the "Refresh" and "API Toggle" buttons while resizing to ensure no ANRs occur.
- **Safe Rendering**: Ensure `WidgetSizeCalculator` handles large dimensions without overflow.
    - Verify `renderGraph` stops drawing when its scope is canceled.
- **Performance Audit**: Check `SET_VIEW_SLOW` logs in the database to ensure average update time remains low under load.
