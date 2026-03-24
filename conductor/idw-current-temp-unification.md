# Plan: Unify Current Temperature Resolution with Graph IDW & Forward Extrapolation

## Objective
Ensure that the "current temperature" displayed at the top-left of the widget is consistent across all view modes (Daily, Temperature, Precipitation, Cloud Cover) by using the same logic used for the Hourly Temperature graph: Inverse Distance Weighting (IDW) spatial blending and forward extrapolation. Currently, only the Temperature view uses this advanced logic, while other views use a simpler database-level observation resolution, leading to discrepancies.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`**: Contains the `blendObservationSeries` logic that handles IDW and forward extrapolation.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`**: Manages the Daily forecast view.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`**: Contains logic for rendering daily forecast columns.
- **`app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`**: The main entry point for widget updates; orchestrates data fetching and handler calls.
- **`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`**: Current simple observation resolver.

## Implementation Steps

### 1. Extract Observation Blending Logic
- Create a new file `app/src/main/java/com/weatherwidget/util/ObservationBlender.kt`.
- Extract the following from `TemperatureViewHandler.kt` to `ObservationBlender`:
    - `blendObservationSeries()` (and its result data classes)
    - `buildStationTimeSeries()`
    - `resolveStationPointForTimestamp()`
    - `StationTimeSeriesPoint` and related internal data classes.
    - Helper functions for interpolation and extrapolation.
- Ensure `ObservationBlender` is accessible by all handlers and the router.

### 2. Add Helper to `ObservationBlender`
- Add a high-level function `resolveCurrentObservation(observations, hourlyForecasts, displaySource, lat, lon, now)` to `ObservationBlender`.
- This function will:
    1. Calculate the graph time range around `now`.
    2. Call `blendObservationSeries`.
    3. Find the latest blended observation before or at `now`.
    4. Return the temperature and timestamp.

### 3. Update `WidgetIntentRouter.kt`
- Modify `updateHourlyViewWithData` and `refreshDailyView` to:
    1. Fetch raw observations from the database using `repository.getObservationsInRange()` for a window around the current time (e.g., ±12 hours).
    2. Call `ObservationBlender.resolveCurrentObservation()` to get the "graph-style" blended/extrapolated current temperature.
    3. Use this resolved temperature as the primary `observedCurrentTemp` passed to all handlers.
- This replaces the simpler `ObservationResolver.resolveObservedCurrentTemp()` call for the header temperature.

### 4. Update `DailyViewHandler.kt` and `DailyViewLogic.kt`
- Modify `DailyViewHandler.updateWidget` to accept `observedCurrentTemp: Float?` and `observedAt: Long?` parameters.
- Update `DailyViewHandler` to use these parameters for the header resolution instead of computing them from `currentTemps`.
- Pass `observedCurrentTemp` to `DailyViewLogic.prepareGraphDays` and `prepareTextDays`.
- Update `DailyViewLogic` to use the provided `currentTemp` for the `Today` column instead of re-resolving it.

### 5. Update `PrecipViewHandler.kt` and `CloudCoverViewHandler.kt`
- Ensure these handlers correctly pass the provided `observedCurrentTemp` down to the `CurrentTemperatureResolver`.

### 6. Refactor `TemperatureViewHandler.kt`
- Update `TemperatureViewHandler` to use the extracted `ObservationBlender`.
- It can still perform its own full-graph blending for the actuals curve, but the current temperature calculation will now share the same underlying logic as all other views.

## Verification & Testing

### Unit Tests
- **`ObservationBlenderTest.kt`**: New test file to verify IDW blending, gap interpolation, and forward extrapolation.
- **`DailyViewHandlerTest.kt`**: Update to verify that the provided `observedCurrentTemp` is used in the header.
- **`TemperatureViewHandlerActualsTest.kt`**: Update to ensure no regression in graph actuals after extraction.

### Manual Verification
- Deploy the widget and switch between all four view modes.
- Verify that the current temperature at the top-left remains identical across all modes.
- Use `adb logcat -s IDW_BLEND` to confirm that the same blending logic is being executed for each view change.
- Verify that the forward extrapolation is working (e.g., if the latest station observation is 30 minutes old, the displayed temp should follow the forecast trend from that point to "now").
