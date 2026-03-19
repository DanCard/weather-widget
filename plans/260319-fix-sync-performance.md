# Background & Motivation
The `ui_update_alarm` (triggered every 1-2 minutes while charging) is designed to perform lightweight, radio-silent UI refreshes (e.g., temperature interpolation). However, the current implementation of `WeatherWidgetWorker` unconditionally calls `backfillNwsObservationsIfNeeded`, which performs multiple network requests to NWS APIs if historical data is missing or if the device location has slightly changed.

This causes several issues:
1. **Performance**: Network calls during a "UI-only" update can take 10-20 seconds (as observed in recent logs), causing significant lag.
2. **Responsiveness**: User interactions like zooming the graph are queued behind these slow background tasks, leading to the "many seconds" delay reported by the user.
3. **Battery/Efficiency**: Frequent network fetches during what should be a local-only operation defeat the battery-aware refresh policy.

By skipping these network-heavy operations during UI-only refreshes, we ensure the widget remains snappy and respects its "radio-silent" mandate.

# Objective
Fix performance issues during frequent UI-only updates that cause slowness and block user interactions (like zooming). Specifically, prevent network-heavy history backfills and redundant database recomputations from running during lightweight UI refreshes.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`: The background worker that handles both full syncs and UI-only updates.
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`: Contains the logic for NWS history backfill and daily extremes recomputation.
- `app/src/main/java/com/weatherwidget/widget/UIUpdateIntervalStrategy.kt`: Defines the update frequency (very aggressive when charging).

# Implementation Steps

1. **Optimize `WeatherWidgetWorker.doWork`**:
    - Add a check to skip `weatherRepository.backfillNwsObservationsIfNeeded` if `uiOnlyRefresh` is true.
    - Modify `fetchDailyActuals` to accept a `recompute: Boolean` parameter.
    - Only call `weatherRepository.recomputeDailyExtremesFromStoredObservations` if `recompute` is true.
    - Pass `recompute = !uiOnlyRefresh` when calling `fetchDailyActuals`.

2. **Parallelize Widget Updates**:
    - In `WeatherWidgetWorker.updateAllWidgets`, use `coroutineScope` and `launch` to update each widget in parallel instead of sequentially. This will reduce the total time when multiple widgets are present.

3. **Add Performance Logging**:
    - Add `SystemClock.elapsedRealtime()` measurements for each major phase of `doWork`:
        - Data fetch/load (`getWeatherData`)
        - Snapshots/Hourly load
        - Backfill check
        - Daily actuals preparation
        - Total widget update time
    - Log these measurements to `app_logs` if they exceed a reasonable threshold (e.g., 500ms for total, 100ms for individual phases).

4. **Verify WAL Mode Consistency**:
    - Ensure `WeatherDatabase` remains in `WAL` mode to allow concurrent reads during writes. (Verified as already active, but worth keeping in mind).

# Verification & Testing
- **Unit Tests**:
    - Update `WeatherWidgetWorkerTest` (if exists) to verify `backfillNwsObservationsIfNeeded` is NOT called when `uiOnlyRefresh` is true.
- **Instrumented Tests**:
    - Run a sync with `uiOnly=true` and verify via `app_logs` that it completes much faster (target < 500ms).
- **Manual Verification**:
    - On a device, monitor `adb logcat` during a UI update while charging.
    - Verify that no network calls are made to `api.weather.gov` during the UI update.
    - Verify that widget interactions (zoom, tap) remain responsive even when a UI update is enqueued.
