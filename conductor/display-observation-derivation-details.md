# Plan: Display Details of Last Observed Temperature Derivation

## Objective
Provide transparency into how the "last observed temperature" is derived by adding detailed logging to the blending process and displaying these details in the `WeatherObservationsActivity` (Current Stations) screen and potentially via a Toast message on the widget.

## Background
The widget uses Inverse Distance Weighting (IDW) to blend observations from multiple stations and forward extrapolation using forecast trends. This can lead to a "last observed" value that doesn't exactly match any single station reading.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/util/ObservationBlender.kt`**: The core logic for blending and resolving observations.
- **`app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`**: Orchestrates the widget update and calls the blender.
- **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`**: Renders the hourly temperature graph and handles header display.
- **`app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt`**: Shows current observations and logs.

## Implementation Steps

### 1. Enhance `ObservationBlender` with Logging
- Modify `resolveCurrentObservation` to accept an optional `onBlendDebug: ((() -> String) -> Unit)?` parameter.
- Pass this parameter down to `blendObservationSeries`.
- Add logging inside `resolveCurrentObservation` to show which blended point was selected as the "latest".

### 2. Enhance `WidgetRenderer` with Derivation Logging
- In `updateWidgetWithData`, capture the `onBlendDebug` output from `ObservationBlender.resolveCurrentObservation`.
- Resolve the fallback observation via `ObservationResolver.resolveObservedCurrentTemp`.
- Log a comprehensive `OBS_DERIVATION` message to the `app_logs` table, including:
    - Which source was selected (Blended vs Fallback).
    - Blended temperature and its timestamp.
    - Station weights and raw values for that blended point.
    - Fallback temperature and station (if blending failed or as reference).
    - Source and location context.
- Store the latest derivation summary in `WidgetStateManager` so it can be retrieved by the interaction handler.

### 3. Update `WeatherObservationsActivity` to Show Derivation Logs
- Update `WeatherObservationsSupport.matchesFetchLog` to include the `OBS_DERIVATION` tag.
- This will allow users to see the derivation details directly in the "Current Stations" screen logs.

### 4. Provide Derivation Details via Toast on Header Tap
- In `WeatherWidgetProvider.handleToggleViewAction`, before or after the view toggle, trigger a Toast message showing the derivation summary.
- The Toast should show:
    - "Source: [NWS_BLEND | KNUQ | etc.]"
    - "Value: [Temp]° at [Time]"
    - "Stations: [S1 (w=0.8), S2 (w=0.2)]"
- This ensures that every time a user taps the temperature to switch views, they get a brief confirmation of how that number was calculated.

## Verification & Testing
1. **Unit Tests**:
    - Update `ObservationBlenderTest` to verify the new logging parameter doesn't break anything.
2. **Manual Verification**:
    - Build and install on the emulator.
    - Tap the current temperature header on the widget.
    - Verify that a Toast message appears with the derivation summary.
    - Verify that the widget view still toggles.
    - Open the "Current Stations" screen (via the antenna icon or current temp tap).
    - Verify that `OBS_DERIVATION` logs appear in the fetch logs at the bottom.
    - Verify the logs contain the details of station weights and timestamps.
