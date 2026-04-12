# Plan: Fix Samsung "Sun" Day Label Drop on Precipitation Graph

## Objective
Fix an issue where the right-side day label (e.g., "Sun") on the precipitation graph drops down significantly lower than the space available, leaving an awkward gap above the end percentage label (e.g., "71%"). This occurs because the label collision detection only checks widely-spaced vertical positions (12%, 20%, 30%, etc.), skipping over perfectly valid tighter spaces.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt`: Contains the `drawDayLabels` function which iterates over `yFractions` to find a non-colliding vertical slot.

## Implementation Steps
1. **Increase Granularity:** In `GraphRenderUtils.kt`, replace the hardcoded `yFractions = listOf(0.12f, 0.2f, 0.3f, ...)` with a highly granular sequence, e.g., `val yFractions = (4..92 step 4).map { it / 100f }`. This will generate intervals of 4%, 8%, 12%, 16%, 20%, and so on up to 92%.
2. This ensures that if the first slot is blocked by the top of a percentage label, the day label will slide down in small, barely-perceptible increments until it finds free space, rather than dropping massively into the middle of the graph.

## Verification & Testing
- Run the full test suite (`./gradlew test`) to ensure no layout regressions.
- Verify visually on the emulator/device that the rightmost day label ("Sun") slots neatly above or below the percentage label without leaving an excessive, unnatural gap.
