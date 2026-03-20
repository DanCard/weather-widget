# Plan: Fix Hourly Graph X-Axis Warping

## Objective
Fix the non-linear X-axis warping in the hourly temperature graph caused by sub-hourly observations. Currently, the X-coordinate for each point is based on its index in the `hours` list, assuming a constant `hourWidth` per item. When sub-hourly observations (e.g., at 5-minute intervals) are blended with top-of-hour forecasts, those short time intervals take up as much horizontal space as full hours, warping the entire graph and misplacing markers like the "Now" line and the "Observation Dot".

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Main rendering logic where the X-axis points are calculated.
- `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt`: Utility functions for calculating X positions.

## Implementation Steps

### 1. Update `GraphRenderUtils.kt`
- Modify `computeXForTime` and `computeNowX` to use a time-linear calculation based on the start and end of the provided data range.
- Remove the `hourWidth` parameter which assumes index-based spacing.

### 2. Update `TemperatureGraphRenderer.kt`
- In `renderGraph`, calculate the X-coordinate for each `HourData` point based on its `dateTime` relative to the first and last timestamps in the `hours` list.
- Use the total width of the graph and the total time duration to map each point linearly to the X-axis.
- Update calls to `computeNowX` and `computeXForTime` to use the new time-linear logic.

### 3. (Optional) Update other Renderers for Consistency
- Review `CloudCoverGraphRenderer.kt` and `PrecipitationGraphRenderer.kt` to ensure they also use time-linear calculations for their X-axes, even if they currently only have hourly data. (This prevents future warping if sub-hourly data is added to those views).

## Verification & Testing
- **Unit Tests**:
    - Update `GraphRenderUtilsTest` (if it exists) or create new tests to verify `computeXForTime` correctly calculates positions on a linear time scale.
    - Create a test in `TemperatureGraphRendererTest` that provides sub-hourly observations and verifies that the "Now" line and "Observation Dot" are placed at the correct time-proportional positions, not just at index steps.
- **Manual Verification**:
    - Use the emulator to observe the hourly widget when many observations are present. Verify that the "3 PM" label aligns correctly with the 3 PM time, and the "Now" line is in the correct place relative to the hour labels.
    - Verify that the "Observation Dot" age (e.g., "1h 30m ago") matches its visual distance from the "Now" line.
