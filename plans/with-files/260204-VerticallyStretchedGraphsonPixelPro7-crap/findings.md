# Findings - Stretched Graphs on Pixel 7 Pro

## Research & Discoveries
- User reports daily and hourly graphs look stretched vertically on Pixel 7 Pro.
- Looks OK on emulators and Samsung Fold.
- Pixel 7 Pro has high density (420dpi) and tall aspect ratio (19.5:9).

## Graph Rendering Analysis
- Both `TemperatureGraphRenderer` and `HourlyGraphRenderer` use the full available `heightPx` (minus paddings) for the graph area.
- `graphHeight = graphBottom - graphTop`.
- Temperatures are scaled linearly: `y = graphTop + graphHeight * (1 - (temp - minTemp) / tempRange)`.
- On high-density/tall devices, `graphHeight` can be quite large in pixels.
- If `tempRange` is small, the graph will amplify small temperature changes, leading to a "stretched" or "peaky" look.
- Bitmap downscaling occurs if total pixels > 225,000, but aspect ratio is preserved.

## Scaling Logic Observation
- `TemperatureGraphRenderer` has some height-based scaling for fonts, but it's very conservative (max 1.05x).
- `scaleFactor` used for some layout elements is derived from `widthScaleFactor` only.
- `topPadding` and `barWidth` in daily graph depend on `widthScaleFactor`.

## Device Comparison
- **Samsung Fold (RFCT71FR9NT)**: Reported 229dp x 106dp (~2.15:1 ratio). Graphs look "correct".
- **Pixel 7 Pro (2A191FDH300PPW)**:
    - Physical Size: 1080x2340
    - Physical Density: 420 (approx 2.6x density)
    - Aspect Ratio: 19.5:9 (very tall)
    - Observation: The widget likely reports a much higher height relative to its width on this device compared to the Fold, especially if the launcher grid is dense.

## Potential Root Causes
1. **Unconstrained Vertical Scaling**: The graph always uses 100% of the available vertical space for the temperature range. If the widget is tall (like on a Pixel 7 Pro with certain launcher settings), the graph expands vertically without a corresponding horizontal expansion.
2. **Density-Related Scaling**: Although DP is used, perhaps some elements aren't scaling enough on high-density screens, leaving a disproportionately large area for the graph bars/curves.
3. **Small Temperature Range**: If the data has a very small range (e.g. 2 degrees), it fills the entire height.

## Proposed Fix Ideas
- Implement a **minimum temperature range** (e.g., at least 10 degrees) to prevent over-stretching when data is flat.
- **Center the graph** vertically if the allocated height is too large for the width (aspect ratio cap).
- Increase the thickness of bars/curves on taller widgets.