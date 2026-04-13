# Fix Temperature Jump on Graph Scrolling

## Objective
Fix the issue where the "Current Temp" displayed near the top-left of the widget changes noticeably when the user scrolls the hourly temperature/precipitation graph left or right.

## Background & Root Cause
The widget uses an Inverse Distance Weighting (IDW) interpolation over hourly forecast data to smooth the current temperature display between hours. This interpolation relies on the `hourlyForecasts` list spanning the actual current time (`now`).

When the user scrolls the hourly graph, `WidgetIntentRouter` fetches a new list of `hourlyForecasts` centered around the new scroll position (e.g., `now + 6 hours`) and passes this to `TemperatureViewHandler.updateWidget`.

**The Bug:** The `TemperatureViewHandler` (via `TemperatureStateResolver`) uses this *same* shifted `hourlyForecasts` list to resolve the current temperature. If the user scrolls far enough, `now` falls out of the bounds of the provided list, or the list boundaries alter the smoothing context. This causes `CurrentTemperatureResolver` to fail interpolation and fall back to the raw, un-smoothed API observation, resulting in a noticeable jump in the displayed value. 

## Proposed Solution
Decouple the `hourlyForecasts` used for drawing the graph from the `hourlyForecasts` used to interpolate the current temperature.

### 1. Centralize Current Temp Forecast Loading
- Move the private `loadCurrentTempResolutionHourlyForecasts` method from `WidgetIntentRouter` to a public/internal utility object (e.g., `CurrentTempDataUtils` or inside `CurrentTemperatureResolver.Companion`).

### 2. Update View Handler Signatures
Add an optional `currentTempHourlyForecasts: List<HourlyForecastEntity>? = null` parameter to:
- `TemperatureViewHandler.updateWidget`
- `PrecipViewHandler.updateWidget`
- `CloudCoverViewHandler.updateWidget`
- `TemperatureStateResolver.resolve`

### 3. Use the Correct Forecasts for Current Temp Resolution
In `TemperatureStateResolver`, `PrecipViewHandler`, and `CloudCoverViewHandler`, modify the call to `CurrentTemperatureResolver.resolve`:
```kotlin
val resolveForecasts = currentTempHourlyForecasts ?: CurrentTempDataUtils.loadResolutionWindow(context, lat, lon, now)

val currentTempResolution = CurrentTemperatureResolver.resolve(
    now = now,
    displaySource = displaySource,
    hourlyForecasts = resolveForecasts, // USE THE STABLE WINDOW HERE
    // ...
)
```

### 4. Update Callers
- **`WidgetIntentRouter`**: In `updateHourlyViewWithData`, it already computes `currentTempHourlyForecasts`. Pass this list explicitly into the view handlers (`TemperatureViewHandler.updateWidget`, etc.).
- **`WidgetRenderer`**: In `updateWidgetInternal`, either compute `currentTempHourlyForecasts` from the database and pass it, or pass `null` and allow the view handlers to self-resolve using the database utility.

## Verification
- Install the widget and ensure the Daily view shows the correct current temperature.
- Tap the current temperature to open the Hourly Graph.
- Scroll the graph left and right (e.g., -6h, +6h, +12h).
- **Assertion:** The "Current Temp" string at the top left should remain perfectly stable and identically matched to the Daily view, regardless of scroll position.