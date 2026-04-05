# Plan: Inject Daily Extremes into Hourly Graph

## Objective
Resolve the discrepancy between the Daily View's official forecast extreme (e.g., 85°) and the Hourly Graph's maximum data point (e.g., 84°) by artificially injecting the missing daily extreme as a sub-hourly data point directly into the graph data. This forces the cubic spline to mathematically and visually peak exactly at the official daily high.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`: Orchestrates widget data fetching and delegates to handlers.
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`: Prepares the state for the temperature graph.
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureHourDataBuilder.kt`: Assembles the `HourData` points for the graph.

## Implementation Steps

### 1. Pass Official Daily Extremes to the Data Builder
- **`WidgetIntentRouter.kt`**: Extract the `today` forecast high and low (using `fallbackWeather?.highTemp` and `fallbackWeather?.lowTemp`, or the calculated `visibleHigh`/`visibleLow` from `DailyActualsEstimator`).
- **`TemperatureStateResolver.kt`**: Accept `todayForecastHigh: Float?` and `todayForecastLow: Float?` as parameters in the `resolve` function.
- Pass these values down into `buildHourDataList` (and `buildHourDataResult`) inside `TemperatureHourDataBuilder.kt`.

### 2. Inject the Daily Extreme Data Points
- **`TemperatureHourDataBuilder.kt`**: In `buildHourDataResult`, after the initial `hours` list is fully populated (which contains top-of-the-hour forecasts and actual observations):
  - Identify the maximum and minimum hourly temperatures for the current day.
  - If a `todayForecastHigh` is provided and is strictly greater than the maximum hourly point for today (e.g., Daily = 85, Hourly max = 84), artificially inject a new `HourData` point.
  - **Placement Logic**: Find the index of the hourly max. Check its immediate neighbors to determine the "slope" (e.g., if 2 PM is 82, 3 PM is 84, and 4 PM is 84, the peak is between 3 PM and 4 PM). Create a new `HourData` with `dateTime` set to 30 minutes offset from the peak hour in the direction of the highest neighbor.
  - **Properties**: The injected `HourData` will have `temperature = todayForecastHigh`, `isActual = false`, `showLabel = false`, and inherit the icon/cloud cover from the nearest hour.
  - Repeat the process for `todayForecastLow` if it is strictly less than the minimum hourly point.
  - Re-sort the `hours` list chronologically by `dateTime` before returning it.

### 3. Let Existing Logic Handle Rendering
- No changes are needed in `TemperatureGraphRenderer.kt` or `GraphRenderUtils.kt`.
- The existing X-axis scaling properly handles sub-hourly data points (it was built to handle random observation times).
- The existing cubic spline logic (`computeTangents`) handles non-uniform spacing gracefully.
- The existing label placement logic (`forecastHighIndex`) automatically finds the maximum `temperature` in the list, which will now correctly be our injected 85° point, and labels it perfectly.

## Verification & Testing
- **Unit Tests**:
    - Update `TemperatureHourDataBuilderTest` (if it exists) to mock a scenario where the provided `todayForecastHigh` exceeds the hourly list's max, and verify that the returned list contains the injected sub-hourly point at the correct temperature and interpolated timestamp.
- **Manual Verification**:
    - Select a weather API source (e.g., NWS) where the Daily High is known to be slightly higher than the max Hourly point.
    - Open the Hourly Temperature Graph.
    - Verify that the curve visually arcs to reach the 85° point and the peak label correctly reads "85°", matching the Daily View's number exactly without causing any X-axis warping.
