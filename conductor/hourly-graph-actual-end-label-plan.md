# Plan: Label at End of Actuals Graph Line

## Objective
Add a temperature label at the end of the "actuals" (solid) line in the hourly temperature graph. This provides a clear reading of the last known observed temperature before the forecast begins. The user noted this is especially useful in "zoomed in" views (where there is more horizontal space, allowing the label to clear collision detection).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Handles the drawing of the hourly graph and its labels. It uses a priority-based collision detection system to place labels (`specialCandidates`).

## Implementation Steps
1. **Locate Label Candidates Logic**:
   In `TemperatureGraphRenderer.kt`, find the label candidate population block (around lines 650-700), where `LOW`, `HIGH`, `FORECAST_HIGH`, and `LOCAL` extrema are added to `specialCandidates`.
   
2. **Add `ACTUAL_END` Candidate**:
   Insert a new candidate for `lastActualIndex` right after the `LOCAL` extrema logic, but before the `START` and `END` logic.
   ```kotlin
   if (lastActualIndex > 0 && lastActualIndex < hours.size - 1) {
       if (specialCandidates.none { it.index == lastActualIndex }) {
           val labelText = String.format("%.1f", smoothedLabelTemps[lastActualIndex])
           // Only add if it doesn't conflict with a nearby label of the same value
           if (specialCandidates.none { Math.abs(lastActualIndex - it.index) <= 3 && labelTextFor(it.labelTemps, it.index) == labelText }) {
               addCandidate(
                   index = lastActualIndex,
                   role = "ACTUAL_END",
                   labelTemps = smoothedLabelTemps,
                   rawTemperature = hours[lastActualIndex].temperature,
               )
           }
       }
   }
   ```
   *Note: `lastActualIndex` is already computed earlier in the `renderGraph` function (`val lastActualIndex = hours.indexOfLast { it.isActual }`).*

3. **Verify Collision Priority**:
   By placing it before `START` and `END`, the `ACTUAL_END` label will have a higher priority than the generic edges of the graph, but will yield to `HIGH`, `LOW`, and significant `LOCAL` peaks/valleys if they overlap. In wider/zoomed-in views, the collision detection will naturally allow this label to render.

## Verification & Testing
- **Unit Testing**: Add a test case in `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt` (or similar label test file) to provide mock hourly data with both actuals and forecast, capture the `onLabelPlaced` callbacks, and assert that a label with `role == "ACTUAL_END"` is generated and corresponds to the index of the last actual observation.
- **On-Device Verification**: Deploy to a device/emulator and observe the hourly graph.
- Verify that a label appears at the last "actual" point (where the solid yellow line ends and the fetch dot/dashed forecast line begins).
- Resize the widget to a wider layout ("zoomed in") and confirm the label appears reliably.
- Ensure the label does not overlap with existing `HIGH`, `LOW`, or `LOCAL` extrema labels (collision detection should hide it or place it on the opposite side).