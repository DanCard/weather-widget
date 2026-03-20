# Fix Missing Peak Label on Hourly Graph

## Answer to: "What happens if we remove top padding?"
If we remove `topPadding` (setting `GRAPH_TOP_PADDING_DP = 0f`), the graph curve is allowed to draw all the way to the very top edge of the widget (`y = 0`). 

Because Android's View system strictly clips drawing to the bounds of the widget (`0` to `heightPx`), any text drawn *above* the graph line (Attempt 1) at `y = 0` would have a negative vertical coordinate. Consequently, the label would be **completely invisible** because it's cut off by the top edge of the screen.

Removing top padding would guarantee Attempt 1 always fails for peaks, forcing the system to rely on Attempt 2 (drawing below the line). On small widgets or foldables, Attempt 2 often fails because the label intersects with the bottom icons. To fix the issue, we actually need **more** headroom above the graph so Attempt 1 can succeed, not less.

---

## Objective
Ensure the peak temperature label is consistently displayed on the hourly forecast graph, especially on devices or emulator configurations with constrained vertical space (like foldables or small widgets).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

## Background
On emulators or small widget sizes, the peak of the forecast graph often goes unlabeled. This occurs because the primary attempt to place the label above the peak (`Attempt 1`) fails the `bounds.top >= 0f` check. The default `TOP_TEMP_BUFFER_RATIO` (0.1f) combined with an 8dp top padding is insufficient to fit a ~20dp text label completely on-screen when the temperature peak is near the maximum graph boundary.

When Attempt 1 fails, it falls back to Attempt 2 (placing the label below the line). However, on vertically constrained graphs, placing the label below pushes it into the icon area at the bottom (`drawnIconBounds`), causing Attempt 2 to also fail. Consequently, the label is completely omitted.

Additionally, the `isPeak` logic evaluates to false for local extrema that form a plateau, causing the algorithm to incorrectly prefer drawing below the line.

## Implementation Steps

1. **Update Buffer Ratios (Create Headroom)**:
   - In `TemperatureGraphRenderer.kt`, increase `TOP_TEMP_BUFFER_RATIO` from `0.1f` to `0.2f` to compress the graph slightly and provide more consistent headroom above the graph for labels.

2. **Improve `isPeak` and `isValley` Plateau Logic**:
   - Update the local extremum peak detection in the label drawing loop to look past identical values (plateaus) to properly identify if it's a peak or valley:
     ```kotlin
     val leftVal = smoothedLabelTemps.subList(0, idx).findLast { it != smoothedLabelTemps[idx] } ?: 0f
     val isPeak = (idx == dailyHighIndex || (idx in significantLocalExtrema && smoothedLabelTemps[idx] > leftVal))
     ```

3. **Add Fallback for Global Extrema**:
   - In the `specialIndices` loop, if both placement attempts fail and the index is `dailyHighIndex` or `dailyLowIndex`, implement a fallback mechanism that forcefully draws the label.
   - For `dailyHighIndex`, clamp the Y-coordinate so the top of the text doesn't go above `0f` (e.g. `Math.max(textHeight, sy - dpToPx(5f))`).
   - For `dailyLowIndex`, clamp the Y-coordinate so the bottom of the text doesn't exceed `heightPx`.
   - This guarantees the absolute high and low of the graph are never dropped, even on extremely squished layouts.

## Verification
- Run the emulator to verify that the highest point on the hourly graph is successfully labeled.
- Verify that the label does not overlap the widget's top edge.