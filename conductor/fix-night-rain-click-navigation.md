# Fix Night Rain Click Navigation

## Objective
Fix a bug where clicking the night rain chance label in the daily forecast view incorrectly navigates to the Cloud Cover graph instead of the Precipitation graph.

## Background & Motivation
The widget uses a 6x20 invisible grid (`graph_night_rain_zones`) to overlay touch targets onto the rendered night rain labels. Currently, this grid is constrained to the bottom `48dp` of the widget layout, and the row calculations in `NightRainGridMapper` return all 6 rows for the target columns. 
With recent updates that allow night rain labels to be placed interstitially (higher up in the graph, between high and low temperature labels), the labels can be drawn well outside the bottom `48dp` zone. For example, logs show baselines ranging from `266px` to `523px` depending on the widget size and temperature graph shape. Because the labels move dynamically based on the temperature curves, simply moving the `48dp` zone higher up is insufficient. The zone must cover the full height of the graph to guarantee intercepting clicks on dynamically placed labels. When a user taps a label that is drawn outside the touch zone, the touch falls through to the underlying `graph_bottom_day_zones` layer. This underlying layer handles clicks for the daily icon stack and resolves to `ViewMode.CLOUD_COVER` when the forecast indicates clouds/rain.

## Scope & Impact
This fix is localized to the widget layout and the `NightRainGridMapper`. By making the night rain touch grid cover the full height of the graph and properly calculating both X and Y cell coordinates, clicks on the labels will be correctly intercepted regardless of where they are dynamically drawn.

## Implementation Steps
1. **Update Widget Layout**:
   - In `app/src/main/res/layout/widget_weather.xml`, modify the `graph_night_rain_zones` `LinearLayout`. 
   - Change `android:layout_height="48dp"` to `android:layout_height="match_parent"`.
   - Remove `android:layout_gravity="bottom"` as it is no longer necessary.

2. **Update Grid Mapper Logic**:
   - In `app/src/main/java/com/weatherwidget/widget/handlers/NightRainGridMapper.kt`, update the `computeNightRainGridCells` function.
   - Use `labelDraw.topY` and `labelDraw.bottomY` (with a small padding, e.g., 20px, to ensure a reasonable touch target size) to calculate `startRow` and `endRow` relative to `bitmapHeightPx`.
   - Return only the grid cells within `startRow..endRow` and `startCol..endCol`, rather than spanning all rows.

## Verification
- Deploy the widget to the emulator.
- Switch to the daily view and ensure a night rain chance label is visible (interstitial or bottom-anchored).
- Click the night rain chance label and confirm it correctly opens the Precipitation graph centered on the corresponding night.