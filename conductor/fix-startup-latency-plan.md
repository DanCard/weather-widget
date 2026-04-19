# Plan: Widget Startup Latency Fixes

## Objective
Fix the ~7 second widget drawing latency after an app update (or reinstall) on the emulator and devices by optimizing startup execution, making UI rendering coroutines cancellable, and implementing smarter job tracking.

## Background & Motivation
When the app is updated, `MY_PACKAGE_REPLACED` and `APPWIDGET_UPDATE` broadcasts fire back-to-back. The widget tries to render immediately but also queues a background worker since the data might be stale. These overlapping requests cause "thundering herd" concurrency issues. `WidgetUpdateTracker` cancels the UI job when the background worker arrives, but because the CPU-intensive `renderGraph` functions are not checking for cancellation (no `ensureActive()`), they continue to consume CPU cycles, delaying the eventual final render.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`: Handles broadcasts and initiates UI rendering/workers.
- `app/src/main/java/com/weatherwidget/widget/WidgetUpdateTracker.kt`: Tracks and cancels active widget jobs.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` (and other renderers): CPU-heavy canvas rendering logic.

## Implementation Steps

### 1. Make UI Render Cancellable
Add `ensureActive()` from `kotlinx.coroutines` to the loops and major data processing steps within the graph renderers. This ensures that when a coroutine is cancelled by `WidgetUpdateTracker`, it immediately stops CPU work and throws a `CancellationException`.
- Update `DailyForecastGraphRenderer.renderGraph`
- Update `TemperatureGraphRenderer.renderGraph`
- Update `PrecipitationGraphRenderer.renderGraph`
- Update `CloudCoverGraphRenderer.renderGraph`

### 2. Smart Job Cancellation
Update `WidgetUpdateTracker` to differentiate between "UI Paint" jobs and "Background Sync" jobs. A background sync job should *not* immediately cancel a UI paint job if the UI paint job is actively trying to draw the cached data.
- Refactor `WidgetUpdateTracker.trackJob` to accept an optional `jobType` enum (`UI_PAINT`, `BACKGROUND_SYNC`, `INTERACTION`).
- Add logic to skip cancellation if a `BACKGROUND_SYNC` tries to step on a `UI_PAINT`.

### 3. Debounce Broadcasts
Prevent rapid, duplicate updates from overlapping in `WeatherWidgetProvider`.
- In `WeatherWidgetProvider.onReceive`, debounce back-to-back `APPWIDGET_UPDATE` or `MY_PACKAGE_REPLACED` calls within a short time window (e.g., 500ms) for the same widget.

## Verification & Testing
- Run `./gradlew test` to ensure logic changes haven't broken the test suite.
- Re-run `./scripts/emulator-tests.sh` to ensure instrumented tests still pass.
- Observe the widget startup time using the existing performance logging (`adb logcat | grep WIDGET_STARTUP_PERF`) and visual verification on the emulator after a fresh install. Latency should be significantly reduced.
