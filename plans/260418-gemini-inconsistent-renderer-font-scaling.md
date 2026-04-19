# Unify and Enlarge Daily Graph Renderers

## Background & Motivation
The user observed a visual discrepancy between Samsung and Pixel devices: on Samsung, the daily high temperature text in the widget graph is significantly larger than the fixed XML `current_temp` (which is 26sp). On Pixel, it is the reverse. The user prefers the bold, magnified look of the Samsung rendering.

This happens because Samsung devices often request very large widget dimensions, forcing `WidgetSizeCalculator` to downscale the bitmap resolution (`bitmapScale < 1.0`). While some elements correctly scale their `dp` sizes down to match the downscaled bitmap, the `DailyForecastGraphRenderer`, `PrecipitationGraphRenderer`, and `CloudCoverGraphRenderer` fail to scale down key elements like `tempLabelHeight`, `iconSize`, and `barWidth`. When the Android `ImageView` stretches this downscaled bitmap back to full size, those unscaled elements are inadvertently magnified by 1.5x–2.0x.

## Scope & Impact
- **Impact:** Fixes the inconsistent scaling behavior between devices by implementing `bitmapScale` correctly across all renderers. Simultaneously, it increases the base sizes of the affected elements so that *all* devices (including Pixel) match the large, readable style the user prefers on Samsung.
- **Scope:** Modifies `DailyForecastGraphRenderer.kt`, `PrecipitationGraphRenderer.kt`, and `CloudCoverGraphRenderer.kt`. No logic changes; strictly visual scaling and base size adjustments.

## Proposed Solution
1. **Unify `labelScale`:** For each affected renderer, define `val labelScale = bitmapScale.coerceIn(0.5f, 1f)`.
2. **Apply `labelScale` to Constants:** Multiply the inputs to all `dpToPx` or `spToPx` calls defining physical dimensions (e.g., `textSize`, `topPadding`, `bottomPadding`, `iconSize`, `strokeWidth`, `barWidth`) by `labelScale` so they scale proportionally on the downscaled bitmap.
3. **Increase Base Sizes:** Increase the base unscaled sizes of fonts and icons in these renderers to match the magnified Samsung look. For example, increase the `DailyForecastGraphRenderer`'s `tempLabelHeight` from `10.5f` to ~`18f`, and `iconSize` from `16f` to ~`24f`. Apply proportional increases to Precipitation and Cloud Cover renderers.
4. **Fix Narrow Widget Logic:** In `DailyForecastGraphRenderer.kt`'s `resolveTopDateTextSizePx`, correct the `widthDp` calculation to reflect the *original* widget width, not the downscaled width, preventing false "narrow" mode triggers.

## Implementation Steps
### 1. `DailyForecastGraphRenderer.kt`
- Increase base sizes in `computeLayout`:
  - `tempLabelHeight` base from `10.5f` to `18f`
  - `iconSize` base from `16f` to `24f`
  - `barWidth` base from `2.2f` to `3f`
  - `tripleBarWidth` base from `1.4f` to `2f`
- Apply `val labelScale = bitmapScale.coerceIn(0.5f, 1f)` to: `tempLabelHeight`, `iconSize`, `topPadding`, `barWidth`, `tripleBarWidth`.
- Ensure `getPaintSet` correctly passes or uses `labelScale` (e.g., `rainTextPaint` scale from `8f` to `12f`).
- Update `resolveTopDateTextSizePx` to calculate the true `widthDp` using the original, unscaled screen dimensions.

### 2. `PrecipitationGraphRenderer.kt`
- In `renderGraph`, define `val labelScale = bitmapScale.coerceIn(0.5f, 1f)`.
- Increase base sizes and apply `labelScale`:
  - `iconSize` base from `16f` to `24f`
  - `labelHeight` base from `10f` to `14f`
  - `topPadding` base from `18f` to `24f`
- Apply `labelScale` to all `Paint` initializations (`hourLabelTextPaint`, `percentLabelPaint`, `nowLabelTextPaint`, `dayLabelTextPaint`) and increase their base sizes by ~1.5x (e.g., 11f -> 16f, 13f -> 18f).

### 3. `CloudCoverGraphRenderer.kt`
- In `renderGraph`, define `val labelScale = bitmapScale.coerceIn(0.5f, 1f)`.
- Increase base sizes and apply `labelScale` similar to Precipitation:
  - `iconSize` base from `16f` to `24f`
  - `labelHeight` base from `10f` to `14f`
  - `topPadding` base from `18f` to `24f`
- Apply `labelScale` to all `Paint` initializations (`hourLabelTextPaint`, `percentLabelPaint`, `nowLabelTextPaint`, `dayLabelTextPaint`) and increase their base sizes by ~1.5x.

## Verification
- Review the code to ensure `bitmapScale` is consistently applied to all static rendering dimensions across the three classes.
- Build and verify the Pixel widget now matches the bold, large-text appearance of the Samsung widget.
