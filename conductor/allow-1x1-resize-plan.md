# Plan: Allow 1x1 Widget Resize and Drop Controls for Narrow Widgets

## Objective
Enable the weather widget to be resized to a 1x1 configuration on the home screen. When the widget is extremely narrow (1 home screen column wide, or `numColumns == 1`), automatically hide the API source text, settings gear icon, and the right-side padding to maximize space for the forecast data. The "yesterday" column is also omitted in these narrow constraints.

## Changes

1. **Enable 1x1 Resize (`app/src/main/res/xml/weather_widget_info.xml`)**:
   - Update `android:minResizeWidth`, `android:minResizeHeight`, `android:minWidth`, and `android:minHeight` to `40dp`. This allows Android launchers to shrink the widget to a single 1x1 cell footprint.

2. **Hide API/Settings in Text Mode (`DailyViewHandler.kt`)**:
   - Update `setTextModeViews(views)` to accept `numColumns`.
   - Call `setSingleRowControlsVisible(views, numColumns > 1)` so the single-row text-mode API and gear icons are hidden when only 1 column wide.

3. **Hide API/Settings in Graph Mode (`DailyViewHandler.kt`)**:
   - Update `setGraphModeViews(views)` to accept `numColumns` and conditionally set `api_source_container`, `api_touch_zone`, `settings_icon`, and `settings_touch_zone` to `View.GONE` if `numColumns == 1`.
   - When building `DailyForecastGraphRenderer.HeaderRenderData`, set `apiSourceText = null` and `settingsIconRes = 0` if `numColumns == 1`.

4. **Remove Right Padding for 1-Column Widget (`DailyViewHandler.kt`)**:
   - When calculating the root and content right padding for text mode, check if `numColumns == 1`.
   - If true, set `TEXT_MODE_ROOT_RIGHT_PADDING_DP` and `TEXT_MODE_CONTENT_RIGHT_PADDING_DP` to 0 (or apply `0` padding directly) to reclaim space.

## Notes
- **Yesterday Column**: `NavigationUtils.getDayOffsets` already enforces starting from "Today" (offset `0L`) whenever `numColumns <= 2`. Therefore, the "yesterday" column is already correctly dropped for 1-column widgets, fulfilling this requirement automatically.
- **Show as Many Days as Fit**: The `numColumns` calculation (`(width + 15) / 70`) accurately represents how many days can visually fit. The renderer will naturally display the maximum number of full days the horizontal space supports.

## Verification
- Resize the widget to 1x1 on the emulator. Ensure the API source and gear icon vanish, and the right padding is stripped away so the "Today" forecast takes up the full width.
- Resize to 2x1 and confirm the gear/API icons reappear (or stay hidden if the width calculation still rounds to 1 column). Verify yesterday is dropped and 2 days are shown if it calculates to 2 columns.