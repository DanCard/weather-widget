# Plan: Unify Label and Icon Sizes across Cloud and Precipitation Graphs

Increase label and icon sizes in the Cloud Cover and Precipitation graphs to match the larger, more readable styles used in the Temperature graph, and remove redundant features from the Precipitation graph.

## Objective
Standardize the visual hierarchy across all hourly graphs by aligning the text and icon dimensions of the Cloud and Rain views with the established Temperature graph standards, and ensure feature parity by removing the fetch dot from the Precipitation graph.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`**: Manages cloud graph rendering and label sizes.
- **`app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`**: Manages rain graph rendering.
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphStyle.kt`**: Source of truth for standard label sizes (23dp for main, 15.5dp for NOW).
- **`app/src/main/java/com/weatherwidget/widget/GraphLayout.kt`**: Source of truth for standard hourly icon size (22.4dp).

## Implementation Steps

### 1. Update CloudCoverGraphRenderer.kt
- Update `ensurePaints`:
    - `hourLabelTextPaint.textSize`: `18.0f` -> `23.0f` (multiplied by `labelScale`).
    - `percentLabelPaint.textSize`: `16.0f` -> `23.0f`.
    - `nowLabelTextPaint.textSize`: `12.0f` -> `15.5f`.
    - `dayLabelTextPaint.textSize`: `18.0f` -> `23.0f`.
- Update `renderGraph`:
    - `iconSize`: `24f * labelScale` -> `22.4f`.
    - `iconSizePx` (watermark): `20f` -> `24f`.
    - `labelHeight`: Increase to ~`20f` to prevent day label clipping.

### 2. Update PrecipitationGraphRenderer.kt
- **Remove Fetch Dot Logic:** Delete the section in `renderGraph` that calls `GraphRenderUtils.drawFetchDot` and any related calculations for `fetchX`, `fetchY`, and `ageMinutes`.
- Refactor to implement `ensurePaints` and a `PaintSet` private class, matching the pattern in `CloudCoverGraphRenderer`.
- Apply standardized text sizes:
    - Hour/Percent/Day labels: `23.0f` (multiplied by `labelScale`).
    - "NOW" label: `15.5f`.
    - `rainAmountPaint`: `10f` -> `18f`.
- Standardize icon sizes:
    - Hourly `iconSize`: `22.4f`.
    - Watermark `iconSizePx`: `24f`.
- Ensure the `labelHeight` and `topPadding` are updated to match the larger text requirements.

## Verification & Testing

### Automated Tests
- Run `PrecipitationGraphRendererTest` and `CloudCoverGraphRendererTest` to ensure no rendering regressions or crashes.
- Update any specific size-related assertions in the tests to match the new 23dp/22.4dp values.
- Verify that tests expecting a fetch dot in Precipitation graph are updated or removed.

### Visual Verification
- Use the emulator to perform a visual audit of the Cloud and Rain graphs.
- Verify that:
    - Labels are larger and consistent with the Temperature graph.
    - Icons are appropriately sized and aligned.
    - The fetch dot is NO LONGER drawn on the Precipitation graph.
    - Labels do not overlap excessively due to the increased size.
    - Watermark icons are clearly visible but not intrusive.
