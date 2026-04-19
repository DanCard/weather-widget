# Header Day Overlap Fix Plan

## Objective
Prevent the header text (e.g., "Sunday • NWS") in the hourly graphs (Temperature, Precipitation, Cloud Cover) from overlapping with the center navigation icons on narrower widgets.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
- `app/src/main/java/com/weatherwidget/util/WeatherTimeUtils.kt` (or similar utility class)

## Implementation Steps
1. **Create formatting helper:** Create a new utility function (e.g., `HeaderFormatter.formatSourceIndicator(centerTime: LocalDateTime, now: LocalDateTime, sourceName: String, widthDp: Int): String`) that calculates the header text based on the available `widthDp`.
   - `widthDp >= 330`: Full day name + source (e.g., "Sunday • NWS").
   - `260 <= widthDp < 330`: Short day name + source (e.g., "Sun • NWS").
   - `widthDp < 260`: Source only (e.g., "NWS").
2. **Update Temperature Resolver:** Update `TemperatureStateResolver.kt` to use this new helper function for the `sourceIndicator`, passing in `dimensions.widthDp`.
3. **Update Precip & Cloud Cover Handlers:** In `PrecipViewHandler.kt` and `CloudCoverViewHandler.kt`, retrieve the `dimensions` (via `stateManager.getWidgetDimensions(appWidgetId)`) before the header formatting block, and use the new helper function to generate their respective `sourceIndicator` variables.

## Verification & Testing
- Test on the emulator by scrolling to a future day and resizing the widget horizontally.
- Observe the day name disappears at `widthDp < 260dp`.
- Observe the abbreviated day name between `260dp` and `329dp`.
- Observe the full day name at `widthDp >= 330dp`.
- Confirm this behavior correctly applies across Temperature, Precipitation, and Cloud Cover graph views without breaking layout constraints.
