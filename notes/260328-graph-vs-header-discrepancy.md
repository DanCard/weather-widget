# Discrepancy Between Current Temp Header and Hourly Graph Visuals

## The Issue
Sometimes, the "Current Temp" header value contradicts the visual trend shown on the hourly temperature graph. 
For example: The last observed temp is `75.1`. The "Current Temp" header shows `75.9`. However, the visual graph line past the last observed point appears to be trending downwards, not upwards.

## The Root Cause: Disconnected Smoothing Logic

The discrepancy is caused by the different ways the widget calculates text values versus how it renders visual lines. The visual graph applies mathematical smoothing to the forecast data to make it look pleasing, while the text header relies on raw, unsmoothed data.

### 1. The Mathematical Smoothing
Before the graph is drawn, a 3-point moving average (`GraphRenderUtils.smoothValuesPreservingGlobalExtrema`) is applied to specific data series:

*   **The Observed/Actuals Line**: Is **NOT** mathematically smoothed. It plots the exact raw observation data points.
*   **The Forecast Line**: **IS** mathematically smoothed. The algorithm "melts down" local, minor spikes and dips to create a broader trend curve.
*   **The Expected Line (The forward trend)**: **IS** mathematically smoothed. This line is simply a copy of the mathematically smoothed Forecast line, shifted up or down (using an `anchorDelta`) so that it connects seamlessly with the Last Observed point.

When looking at the graph *past* the "Last Observed" point (e.g., `75.1`), you are looking at the **Expected line**. Because this line is based on the smoothed forecast, it may have mathematically flattened out a temporary upward spike in the raw data, resulting in a visual downward trend.

### 2. The Text Header Calculation
Meanwhile, the "Current Temp" header (e.g., `75.9`) ignores the smoothed graph arrays entirely. It is calculated in `CurrentTemperatureResolver.kt` using strict **linear interpolation** between the **raw, unsmoothed Forecast line** points.

If the raw forecast data had a brief spike up to `75.9`, the text header will linearly trace that spike upwards and display `75.9`. At the exact same time, the graph's mathematical smoothing has visually "ironed out" that spike, drawing a downward curve.

### 3. The Visual Smoothing (Bezier Splines)
As an additional layer of divergence, **all lines** on the graph (Observed, Forecast, and Expected) are drawn on the screen using a **Cubic Bezier Spline** (`buildSmoothCurveAndFillPaths`) rather than straight, jagged point-to-point lines. 

While this Bezier spline is "monotone-aware" (it attempts to prevent wild overshoots at peaks and valleys), it still bends the path of the line to look visually fluid. The "Current Temp" text interpolation is strictly linear (straight lines between points), meaning the visual Y-coordinate of the curve at any given minute will slightly differ from the linear mathematical interpolation of that exact same minute.

## Potential Fixes
To resolve this visual inconsistency in the future, two main options exist:
1.  **Apply Smoothing to the Header**: Extract the smoothing logic so that `CurrentTemperatureResolver` interpolates based on the *smoothed* forecast points rather than the raw points.
2.  **Remove/Reduce Smoothing on the Graph**: Reduce the smoothing iterations or render the exact raw points so the graph perfectly matches the text.
