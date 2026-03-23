# Plan: Exaggerate Rain Chance Graph (Dynamic Y-Axis)

## Objective
Exaggerate the precipitation graph when rain chances are low so that the line isn't "very flat" on the emulator and devices. We will dynamically scale the Y-axis based on the maximum probability in the current view, applying a minimum ceiling (e.g., `40f`) so tiny chances don't misleadingly take up the entire screen height.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Contains the graph rendering logic, specifically `renderGraph` where the Y-coordinates are calculated as a percentage of `100f`.

## Implementation Steps

1. **Calculate `yScaleMax`**:
   In `PrecipitationGraphRenderer.kt` (around line 208-210), after defining `smoothedProbs`, determine the maximum scale for the Y-axis:
   ```kotlin
   val yScaleMax = smoothedProbs.maxOrNull()?.coerceAtLeast(40f) ?: 100f
   ```

2. **Apply Scaling to Curve Points**:
   Update the loop that builds `points` to use `yScaleMax` instead of `100f`:
   ```kotlin
   hours.forEachIndexed { index, _ ->
       val x = hourWidth * index + hourWidth / 2f
       val prob = smoothedProbs[index]
       val y = graphBottom - graphHeight * (prob / yScaleMax)
       points.add(x to y)
   }
   ```

3. **Apply Scaling to Fetch Dot**:
   Update the fetch dot Y calculation (around line 912) to use `yScaleMax`:
   ```kotlin
   val fetchY = graphBottom - graphHeight * (interpolatedProb / yScaleMax)
   ```

## Verification & Testing
- Deploy to emulator/device and view a forecast with low precipitation chances (e.g., max 15-20%).
- Verify that the curve fills significantly more of the vertical space (scaling up to the 40% visual line).
- Verify that forecasts with high precipitation (e.g., 80-100%) still render properly without clipping.
- Ensure the fetch dot (if visible) aligns accurately with the newly scaled curve.
