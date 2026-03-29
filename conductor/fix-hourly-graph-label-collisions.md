# Plan - Fix Hourly Graph Label Collisions and Redundant Values

Address visual clutter on the hourly temperature graph by deduplicating redundant temperature labels (Peak vs. Fetch Dot) and preventing the "NOW" label from overlapping with other elements.

## Objective
- **Deduplicate Temperature Labels**: If the current observation (Fetch Dot) is also the day's high or low, suppress the yellow landmark label and let the more informative orange Fetch Dot label take precedence.
- **Prevent "NOW" Overlap**: Hide the "NOW" text (while keeping the vertical line) if it overlaps with any temperature or day label.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**: Main renderer for the hourly temperature graph.
- **`app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt`**: Shared utilities for drawing the "NOW" indicator.

## Implementation Steps

### 1. Update `GraphRenderUtils.drawNowIndicator`
- Modify the function signature to accept `drawnBounds: List<RectF> = emptyList()`.
- Calculate the bounding box for the "NOW" text using `nowLabelTextPaint`.
- Only call `canvas.drawText` if the calculated box does not intersect any rect in `drawnBounds`.

### 2. Update `TemperatureGraphRenderer.drawFetchDot`
- Modify `drawFetchDot` to return a `List<RectF>` containing the bounds of the age and temperature labels it draws.
- Ensure these bounds are added to the `drawnLabelBounds` list in the main `renderGraph` function.

### 3. Update `TemperatureGraphRenderer.placeTemperatureLabels`
- In the `specialCandidates` generation logic, add a check to skip adding a landmark (HIGH, LOW, LOCAL) if its index matches the index of the `fetchTime`.
- This suppresses the yellow landmark in favor of the orange Fetch Dot label when they coincide.

### 4. Orchestrate in `TemperatureGraphRenderer.renderGraph`
- Call `drawFetchDot` *before* the "Now" indicator.
- Collect the bounds returned by `drawFetchDot` and add them to the `ctx.drawnLabelBounds`.
- Pass the full `ctx.drawnLabelBounds` and `drawnIconBounds` to `GraphRenderUtils.drawNowIndicator`.

## Verification & Testing

### Manual Verification
- **Scenario A: Current is High**: Deploy to an emulator and observe the graph when the current temperature is the peak. Verify only the orange label (with age) is shown.
- **Scenario B: Now Overlap**: Navigate the graph so the "NOW" line aligns with a temperature label (e.g., at the start of the graph or a peak). Verify the text "NOW" disappears while the vertical line remains.

### Automated Testing
- Run `TemperatureGraphLabelPlacementRobolectricTest` to ensure labels are still placed correctly in non-colliding scenarios.
- Add a new test case to `TemperatureGraphLabelPlacementRobolectricTest` that simulates a "Now" overlap and asserts that the `drawText` call for "NOW" is skipped (via mock/spy if applicable, or by checking a placement-tracking callback).
