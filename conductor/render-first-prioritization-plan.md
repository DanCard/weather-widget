# Implementation Plan: Render-First Prioritization

## Objective
Reduce widget startup latency by deferring non-essential side-effects (database writes, performance logging, and background sync triggers) until after the initial UI rendering is complete.

## Key Changes

### 1. Introduce `DeferredTaskCollector`
Create a simple utility or pattern to collect `suspend () -> Unit` blocks during the rendering process.

### 2. Refactor `WeatherWidgetProvider`
- In `onUpdate`, initialize a list of deferred tasks.
- Move the initial `WidgetPerfLogger.logIfSlow` (for DB open) into a deferred task.
- Pass the collector down to `updateWidgetWithData`.
- Wait for all rendering jobs (`updateWidgetWithData`) to complete using `joinAll()`.
- Execute all collected deferred tasks.
- Move the stale data check (`DataFreshness.isDataStale`) and `triggerImmediateUpdate` into a deferred task.
- Move the final `WidgetPerfLogger.logIfSlow` (for total startup) into a deferred task.

### 3. Refactor View Handlers
Update `DailyViewHandler`, `TemperatureViewHandler`, `PrecipViewHandler`, and `CloudCoverViewHandler`:
- Accept an optional `deferredTasks: MutableList<suspend () -> Unit>` parameter in their `updateWidget` methods.
- Wrap side-effect calls (sync triggers, extra logging) in lambda blocks and add them to `deferredTasks`.
- Specifically defer:
    - `requestMissingDataRefresh` / `requestMissingActualsRefresh` in `DailyViewHandler`.
    - `maybeEnqueueHourlyObservationBackfill` in `TemperatureViewHandler`.
    - Performance logging (`WidgetPerfLogger.logIfSlow`) in all handlers.

### 4. Ensure Lifecycle Safety
Ensure `pendingResult.finish()` in `WeatherWidgetProvider` is only called *after* all deferred tasks have been executed to keep the process alive.

## Implementation Steps

1.  **Modify Handlers**: Update the `updateWidget` signatures in all four handlers to accept the deferred task collector.
2.  **Wrap Side-Effects**: Identify and wrap all identified side-effect calls within the handlers.
3.  **Update Provider**: Refactor `onUpdate` and `updateWidgetWithData` to manage and execute the collector.
4.  **Verification**: Confirm that widgets render immediately and logs/syncs follow shortly after.

## Verification & Testing
- **Unit Tests**: Ensure the logic for identifying sync needs still works when deferred.
- **Manual Testing**: 
    - Verify widget updates on emulator after APK install.
    - Check `app_logs` to ensure performance data and sync requests are still being recorded.
    - Monitor logcat for "onUpdate" completion vs. background worker start.
