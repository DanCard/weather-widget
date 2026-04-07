# Plan: Fix Day Label Placement and Overlap

## Objective
The user reports that the "Mon" label is in a cluttered position and would look better lower. Our previous attempt to move it to the very bottom of the widget resulted in "horrible" overlap with condition icons. We need to find a position that is "lower" than the top of the graph (where it collides with the `START` temperature label) but "higher" than the icons (where it overlaps).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Contains `placeDayLabels`.
- `RenderContext`: Provides `graphTop`, `graphBottom`, `footerTop`.
- **Current Slots**:
    - `TOP`: `graphTop + dayLabelTextHeight` (collides with `START` temp label at `idx=0`).
    - `MIDDLE`: `(graphTop + graphBottom) / 2` (often collides with the temperature curve).
    - `BOTTOM`: `heightPx - 14dp` (overlaps with icons at the bottom of the widget).

## Proposed Solution
Introduce a new slot `BOTTOM_OF_GRAPH` at `ctx.graphBottom` and update the priority order.

1.  **Refactor Slots in `placeDayLabels`**:
    - `TOP_SLOT`: `ctx.graphTop + dayLabelTextHeight` (Original TOP).
    - `GRAPH_BOTTOM_SLOT`: `ctx.graphBottom` (Just above the icons).
    - `MIDDLE_SLOT`: `(ctx.graphTop + ctx.graphBottom) / 2f` (Original MIDDLE).
    - `WIDGET_BOTTOM_SLOT`: `ctx.heightPx - fm.descent - dpToPx(ctx.context, 1f)` (Lower than original, at the very base of the widget).

2.  **Update Priority Order**:
    - Try `GRAPH_BOTTOM_SLOT` first. It's "lower" and likely has space below the curve if the start of the day is warm. It avoids icon overlap entirely.
    - Try `TOP_SLOT` second. (Original behavior).
    - Try `MIDDLE_SLOT` third. (Original behavior).
    - Try `WIDGET_BOTTOM_SLOT` as a fallback, but *only* if it doesn't collide with icons.

3.  **Refine Collision Detection**:
    - For the `WIDGET_BOTTOM_SLOT`, ensure it doesn't overlap with icons.
    - If no slot is found, default to a sensible fallback (e.g. `GRAPH_BOTTOM_SLOT` even if it collides with labels, as it's the most likely "empty" area).

## Implementation Steps
1.  **Modify `placeDayLabels`** in `TemperatureGraphRenderer.kt` to implement the new slot priority and positions.
2.  **Ensure no overlaps** with icons by using the `drawnIconBounds` provided to the function.

## Verification
- Review layout on emulator to ensure "Mon" is lower but not overlapping icons.
- Verify "Tue" on the right side also looks good.
