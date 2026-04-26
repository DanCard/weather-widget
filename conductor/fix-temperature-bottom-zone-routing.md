# Objective
Fix the bug where tapping the bottom hour icons (cloud/rain) in the Temperature view triggers a zoom action instead of navigating to the respective Cloud Cover or Precipitation view. Add a regression test to prevent future occurrences.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewBinder.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureTouchRoutingRoboTest.kt`

# Implementation Steps
1. **Fix TemperatureViewBinder.kt**
   - In `TemperatureViewBinder.kt`, locate the `HourlyBottomZoneHelper.setup` call.
   - Remove the `showBodyOverlayZones = false` argument (or change it to `true`) so that the bottom overlay tap zones are visible and can intercept clicks.
2. **Add Integration Test**
   - In `TemperatureTouchRoutingRoboTest.kt`, add a new test case `bottomRowCloudIcon_navigatesToCloudCoverView`.
   - The test will configure the widget state with `ViewMode.TEMPERATURE`.
   - Render the widget with a mock hourly forecast containing at least one cloud icon (e.g., `R.drawable.ic_weather_mostly_cloudy`).
   - Find `R.id.graph_bottom_hour_zones` and assert it is `View.VISIBLE`.
   - Find the specific bottom hour zone (e.g., `R.id.graph_bottom_hour_zone_0`), verify it is clickable, and perform a click.
   - Assert the broadcasted intent is `WidgetIntentRouter.ACTION_SET_VIEW` with `EXTRA_TARGET_VIEW` set to `CLOUD_COVER`.

# Verification & Testing
- Run the modified `TemperatureTouchRoutingRoboTest.kt` to ensure the new test passes.
- Check the emulator visual state if necessary to ensure no layout issues arise from enabling the overlay.