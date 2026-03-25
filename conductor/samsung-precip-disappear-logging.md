# Plan: Diagnostic Logging for Disappearing Precipitation Graph

This plan adds targeted diagnostic logging to investigate why the precipitation (rain chance) graph disappears after a few seconds on Samsung devices.

## Objective
Identify the root cause of the disappearing precipitation graph by tracking:
1. Widget state resets (API source or view mode).
2. Data availability (hourly forecast counts).
3. Layout fluctuations (reported widget dimensions and row counts).
4. Coroutine cancellations that might interrupt the rendering process.

## Key Files & Context
- **`PrecipViewHandler.kt`**: Coordinates the precipitation view and chooses between graph and text modes.
- **`PrecipitationGraphRenderer.kt`**: Performs the actual drawing of the graph bitmap.
- **`WidgetStateManager.kt`**: Manages the persistent state (API source, view mode) of the widgets.
- **`WidgetIntentRouter.kt`**: Handles resize events, which are frequent on Samsung launchers.
- **`WeatherWidgetWorker.kt`**: Triggers background updates that might reset state or provide incomplete data.

## Implementation Steps

### 1. Track View Mode and Dimension Changes
Add logging to `PrecipViewHandler.kt` to see if the widget is switching modes or receiving empty data.

- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
- **Location**: Inside `updateWidget` function.
- **Log**: `Log.d("PrecipDebug", "Updating widget $appWidgetId: source=${displaySource.id}, hourlyCount=${hourlyForecasts.size}, useGraph=$useGraph, heightDp=${dimensions.heightDp}")`

### 2. Track State Resets
Monitor when the widget's API source or view mode is reset during background syncs.

- **File**: `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- **Location**: Inside `resetAllToggleStates()`.
- **Log**: `Log.d("PrecipDebug", "resetAllToggleStates called - may revert API source to default (NWS)")`

### 3. Detect Empty Render Attempts
Log when the renderer is asked to draw a graph with no data points.

- **File**: `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`
- **Location**: At the start of `renderGraph()`.
- **Log**: `if (hours.isEmpty()) Log.w("PrecipDebug", "renderGraph called with EMPTY hours list for widget")`

### 4. Monitor Resize Events
Samsung devices often trigger rapid resize events during initialization.

- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
- **Location**: Inside `handleResize()`.
- **Log**: `Log.d("PrecipDebug", "handleResize triggered for widget $appWidgetId")`

### 5. Track Worker Completion
Ensure the background worker isn't failing or providing stale data.

- **File**: `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- **Location**: Inside `doWork()`, specifically around the success/failure fold.
- **Log**: `Log.d("PrecipDebug", "Worker sync result: ${result.isSuccess}, force=$forceRefresh, uiOnly=$uiOnlyRefresh")`

## Verification & Testing
1. **Build and Install**: Deploy the debug build to the Samsung device.
2. **Reproduce**: Open the rain chance graph and wait for it to disappear.
3. **Audit Logs**:
   - Use `adb logcat -s PrecipDebug` to filter for the new logs.
   - Check if `useGraph` flips from `true` to `false` (indicating a layout/dimension issue).
   - Check if `hourlyCount` drops to `0` (indicating a data fetch/cache issue).
   - Check if `resetAllToggleStates` correlates with the disappearance (indicating a state reset).
