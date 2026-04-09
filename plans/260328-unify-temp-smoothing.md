# Plan: Unify Current Temperature Smoothing

## Objective
Ensure the "Current Temp" text header precisely matches the visual hourly temperature graph by applying mathematical smoothing to the raw forecast data *before* calculating the current temperature interpolation.

## Background & Motivation
Currently, the widget calculates the Current Temperature header using strict linear interpolation of raw hourly forecast data (`CurrentTemperatureResolver.kt`). However, the visual graph applies a 3-point moving average to the forecast points (`GraphRenderUtils.smoothValuesPreservingGlobalExtrema`) to eliminate stair-step data (especially from NWS integer data) before drawing.

Because the visual graph flattens local bumps, the raw data may briefly spike while the smoothed curve trends downward. This results in the "Current Temp" text showing a rising temperature (e.g., 75.9) while the graph visually trends downward from a lower observed point (e.g., 75.1).

## Proposed Solution
Move the mathematical smoothing logic upstream. We will smooth the list of hourly forecast temperatures *before* it is passed to the interpolator and the renderer. Both the text header and the visual graph will then operate on the exact same array of smoothed temperatures.

### Implementation Steps

#### 1. Add Support for Overriding Forecast Temperatures
Modify `TemperatureInterpolator.kt` to allow passing in overridden temperatures. This avoids needing to instantiate entirely new `HourlyForecastEntity` objects (which require DB ids and complex timestamp mapping) just to pass smoothed data into the interpolator.

- **File**: `app/src/main/java/com/weatherwidget/util/TemperatureInterpolator.kt`
- Add an optional parameter `smoothedForecasts: Map<Long, Float>? = null` to `getInterpolatedTemperature()`.
- If provided, look up the temperature in this map using the hourly timestamp instead of `forecast.temperature`.

#### 2. Apply Smoothing Upstream in the Handler
Before calculating the current temperature or rendering the graph, extract the raw forecast temperatures, smooth them, and map them back to their hourly timestamps.

- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
- Locate where the `hourlyForecasts` are prepared.
- Extract the raw temperatures, maintaining their sequence.
- Apply `GraphRenderUtils.smoothValuesPreservingGlobalExtrema(rawForecastTemps, iterations = zoom.smoothIterations)`.
- Create a `Map<Long, Float>` associating the `HourlyForecastEntity.dateTime` with the newly smoothed temperature.

#### 3. Feed Smoothed Data to Resolver
Update the resolver calls to utilize the new smoothed data map.

- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
- Update the calls to `CurrentTemperatureResolver.resolveQuick()` and `CurrentTemperatureResolver.resolve()` to pass the `smoothedForecasts` map.
- **File**: `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt`
- Pass the map through to `TemperatureInterpolator`.

#### 4. Feed Smoothed Data to Renderer
Ensure the renderer no longer double-smooths the data.

- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
- Update the `buildHourDataList` or the objects passed to the renderer to reflect the smoothed values as their base `temperature`.
- **File**: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- **Crucial**: Remove the local call to `GraphRenderUtils.smoothValuesPreservingGlobalExtrema(rawForecastTemps, iterations = 1)` inside the renderer, as the data arriving here is now pre-smoothed by the handler based on the correct zoom iteration settings.

## Scope & Impact
- **Impacts**: Temperature calculations and rendering in `TemperatureViewHandler`.
- **Does Not Impact**: Daily views, Precipitation views, or Cloud Cover views (unless we explicitly decide to move their smoothing upstream as well, but this plan focuses strictly on temperature).

## Verification & Testing
- Deploy the widget in an emulator with a forced data discrepancy (a local spike in the raw database data that gets flattened by smoothing).
- Verify that the "Current Temp" header accurately reflects the position on the smoothed visual curve, rather than tracing the invisible raw spike.
- Ensure the graph still looks smooth and fluid.
- **Automated Tests**:
    - Add a unit test to `TemperatureInterpolatorTest` to verify that when `smoothedForecasts` is provided, those values are used instead of the raw `HourlyForecastEntity` temperatures.
    - Add a test case in `CurrentTemperatureResolverTest` (create if it doesn't exist) to verify that `resolve()` uses the passed `smoothedForecasts` correctly to compute the current temperature.