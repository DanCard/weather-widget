# Hourly Graph Updates

**Objective**:
Enhance the graphing engine to use time-proportional X-axis spacing. This enables the exact plotting of high-frequency, sub-hourly actual temperatures (e.g., catching a 91.8° spike) without requiring the forecast data to match that same frequency. Additionally, update the graph to display tenths of a digit and remove artificial spline smoothing.

**Key Files & Context**:
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`

**Implementation Steps**:

1. **Time-Proportional X-Axis (`TemperatureGraphRenderer.kt`)**:
   - Refactor the X-coordinate calculation. Instead of `x = index * hourWidth`, calculate `x` as a percentage of the total time window:
     `val x = graphLeft + graphWidth * ((pointTime - minTime) / (maxTime - minTime))`
   - Update any label collision or clustering logic that currently relies on `index` to use `x` coordinates or time-based distance.

2. **Inject Sub-Hourly Actuals (`TemperatureViewHandler.kt`)**:
   - Modify `buildHourDataList` to merge two datasets:
     - **Forecasts**: The standard top-of-the-hour points.
     - **Actuals**: Query the high-resolution `CurrentTempEntity` (or `ObservationEntity`) for the historical window. Insert these points at their exact timestamps.
   - For sub-hourly actual points, set `showLabel = false` and `iconRes = null`. This ensures they provide data for the temperature curve (and are eligible for local extrema labels) without cluttering the bottom axis with overlapping icons or time labels.
   - Sort the combined list chronologically before passing it to the renderer.

3. **Display Tenth of Digit**:
   - In `TemperatureGraphRenderer.kt`, change the local extrema and peak/valley label formatting from `%.0f°` to `%.1f°` (or use a conditional formatter to drop `.0` if exact).
   - In `TemperatureViewHandler.kt`, update the fallback text mode formatting similarly.

4. **Remove Spline Smoothing**:
   - In `TemperatureGraphRenderer.kt`, remove the `GraphRenderUtils.smoothValues` step. Map `smoothedLabelTemps` and `smoothedTruthTemps` directly to the raw temperature values. The high-frequency actual data and the linear time-scale will render a true reflection of the temperature without needing artificial smoothing.

**Verification**:
- The hourly graph correctly displays decimals (e.g., `91.8°`).
- The X-axis does not warp; past hours may have many data points driving the curve, while future hours only have one point per hour, but the physical horizontal distance between 2 PM and 3 PM remains consistent across the graph.
- The history curve accurately hits the exact peaks recorded during the day.
- Spline smoothing logic is removed.