# Fix Rain Chance Spacing and Header Overlap

## Objective
Fix the daily forecast daytime rain probability label so that:
1. It is hidden if it would overlap the widget's header area (e.g., on Samsung and Pixel devices where the high temp column pushes it up).
2. It is drawn closer to the high temperature text (less visual spacing) to look better on the emulator and regular screens.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Contains `drawDailyRainLabel` and `resolveRainAboveHighPlacement`.

## Implementation Steps

1. **Fix `topMargin` to account for the widget header area**:
   In `drawDailyRainLabel`, the `topMargin` used to check if the rain label fits is currently just `2dp` (`RAIN_LABEL_EDGE_MARGIN_DP`). The widget actually has a ~54dp header (`layout.graphTop`).
   - Change `topMargin` to: `layout.graphTop + dpToPx(context, RAIN_LABEL_EDGE_MARGIN_DP * layout.scaleFactor)`.
   - This ensures the rain label is skipped if it crosses into the 54dp header area, fixing the overlap on Samsung/Pixel devices.

2. **Tighten the spacing (`gap`) between the rain chance and high temp**:
   The current OpenAI fix uses `gap = RAIN_LABEL_EDGE_MARGIN_DP` (2dp) added on top of the text's bounding box (`fontMetrics.ascent`). Because Android font bounding boxes include significant internal padding above the visual glyphs, this creates an excessively large visual gap.
   - Change the `gap` in `drawDailyRainLabel` to use a negative margin to counteract the font padding, e.g., `val gap = dpToPx(context, -4f * layout.scaleFactor)`.
   - This reduces the visual spacing, matching the user's preference ("less spacing between rain chance and below high for the day"), while still dynamically scaling with font size to prevent actual overlaps.

## Verification & Testing
- Run `DailyForecastGraphRendererRoboTest` and `DailyForecastGraphRendererTest` to ensure no regressions in layout logic.
- Verify visually on the emulator that the rain chance sits comfortably above the high temperature.
- Ensure that when the high temperature bar is very tall (like on Samsung/Pixel), the rain chance label is completely omitted rather than drawn over the header.