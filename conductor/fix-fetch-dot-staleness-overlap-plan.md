# Fix Fetch Dot Staleness Overlap

## Objective
Prevent the last observation time label (staleness label, e.g., "1h") from overlapping with other labels on the hourly temperature graph. When a collision occurs, the label should be moved above the dot, or if still colliding, pushed further away with a leader line connecting it back to the dot.

## Background & Motivation
Currently, the `ageLabel` in `drawFetchDot()` is statically placed below the observation dot. If the actuals curve dips into this space, or if another label is placed nearby, the staleness label becomes unreadable due to overlap. By dynamically checking for collisions and providing a fallback position (above the dot) and a leader line, we ensure the label is always readable.

## Implementation Steps
1. **Modify `drawFetchDot` in `TemperatureGraphRenderer.kt`:**
   - **Reorder Label Drawing:** Draw the `valueLabel` (e.g., "51.9°") *before* the `ageLabel`. This ensures the value label's bounds can be used to prevent the `ageLabel` from overlapping it.
   - **Implement Collision Detection:** When preparing to draw the `ageLabel`, create a bounding box (`RectF`) for its default position (below the dot).
   - **Check Intersection:** Check if this bounding box intersects with any rectangle in `ctx.drawnLabelBounds` or the bounds of the newly drawn `valueLabel`.
   - **Fallback to Above:** If a collision is detected (or it goes off-screen at the bottom), switch the candidate position to *above* the dot.
   - **Leader Line Bumping:** If the new candidate position *also* collides, incrementally bump the label further away (up or down depending on the chosen side) until clear space is found.
   - **Draw Leader Line:** If the label had to be bumped (step > 0), draw a connecting line from the dot to the label using `ctx.paints.actualLeaderLinePaint`.

2. **Update Debug Data Structures:**
   - Modify `FetchDotDebug` in `TemperatureGraphRenderer.kt` to include `stalenessLabelY: Float?` so tests can verify the final placement of the staleness text.

3. **Add Automated Test:**
   - Open `TemperatureGraphLabelPlacementRobolectricTest.kt`.
   - Add a new test case: `staleness time label is placed above dot when colliding with bottom bounds or other labels`.
   - Configure the test scenario so the `fetchY` is near the bottom of the graph, forcing a collision with the graph's bottom edge or actuals line.
   - Assert that the resulting `stalenessLabelY` in `FetchDotDebug` is *less than* `fetchY` (indicating it was placed above the dot).

## Verification
- Run the newly added test in `TemperatureGraphLabelPlacementRobolectricTest.kt` and ensure it passes.
- On an emulator or device, wait for a state where the actuals graph overlaps the bottom of the fetch dot, or artificially lower the dot during testing.
- Observe the staleness time label ("1h").
- Verify that it relocates above the dot.
- Verify that if placed far away, a leader line correctly connects the label to the dot.
- Ensure the label does not overlap with the blue value label ("51.9°").
