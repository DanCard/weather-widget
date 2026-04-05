# Cloud cover graph body tap zoom fix on emulator

## Problem Description
The user reported a live emulator bug in the cloud-cover hourly graph:

1. Tapping the `17%` label was expected to change zoom.
2. Instead, the widget switched to the temperature graph.
3. The request explicitly referenced the emulator, so runtime evidence on the emulator was treated as the source of truth.

## Prompts History
1. "emulator : cloud cover graph : I click on 17% label.  I'm expecting a zoom change.  Instead I get taken to temperature graph."
2. "emulator : cloud cover graph : I click on 17% label.  I'm expecting a zoom change.  Instead I get taken to temperature graph."
3. "Implement the plan."
4. "do it"
5. "Write detailed session log to session-logs/ dir"

## Investigation & Evidence
1. Read the cloud-cover interaction path in:
   1. `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
   2. `app/src/main/java/com/weatherwidget/widget/handlers/HourlyBottomZoneHelper.kt`
   3. `app/src/main/java/com/weatherwidget/widget/handlers/DayClickHelper.kt`
   4. `app/src/main/res/layout/widget_weather.xml`
2. Confirmed the pre-fix behavior in `CloudCoverViewHandler.setupZoomTapZones(...)`:
   1. graph body zones were inspecting the nearest hour icon
   2. the body zone action was using `DayClickHelper.resolveHourlyBottomRowAction(...)`
   3. that meant a cloud-cover body tap could intentionally navigate to another graph
   4. a clear or temperature-home icon could therefore send the user to temperature view
3. Confirmed the layout structure in `widget_weather.xml`:
   1. the graph body uses `graph_hour_zones`
   2. the bottom icon strip uses `graph_bottom_hour_zones`
   3. the bottom footer uses `graph_bottom_hour_footer_zones`
   4. the `17%` label visually sits above the icon strip, so a body-tap interpretation is reasonable
4. Verified existing tests in `DayClickHelperTest` already covered bottom-row icon routing:
   1. cloud icon on cloud-cover view returns `null` for zoom
   2. clear icon on cloud-cover view navigates to temperature
   3. rain icon on cloud-cover view navigates to precipitation
5. Used emulator and device inspection rather than assuming targets:
   1. `adb devices`
   2. `adb -s <serial> shell "getprop ro.product.manufacturer && getprop ro.product.model"`
   3. confirmed `emulator-5554` was the Android emulator
6. Installed the updated debug build with `./gradlew installDebug`.
7. Inspected live widget state on the emulator:
   1. `dumpsys appwidget` showed the live weather widget instance as widget `25`
   2. `logcat` showed widget `25` running in `CLOUD_COVER` mode with `cols=9, rows=5`
8. Captured the live cloud-cover graph screenshot on the emulator before tapping:
   1. the graph showed the `17%` label near the lower center-right area
   2. the bottom icon strip was visible below it
9. Performed an initial tap too low in the icon band:
   1. log showed `ACTION_SET_VIEW`
   2. target mode was `TEMPERATURE`
   3. screenshot confirmed the widget switched to the temperature graph
   4. this was expected because the bottom strip intentionally preserves icon-based navigation
10. Re-entered cloud-cover mode and tapped higher at the `17%` label in the graph body:
   1. log showed `ACTION_CYCLE_ZOOM`
   2. `WeatherWidgetProvider` logged `handleCycleZoomAction: widget=25 centerOffset=4 currentMode=CLOUD_COVER currentZoom=WIDE`
   3. `WidgetIntentRouter` logged `handleCycleZoom: WIDE -> NARROW`
   4. the post-tap screenshot stayed on cloud cover and zoomed into a narrower `12p` to `4p` range

## Root Cause
1. The cloud-cover graph body and bottom icon strip were sharing the same icon-home routing decision model.
2. That was too aggressive for the cloud-cover body:
   1. body taps on labels or line segments should zoom
   2. only the bottom icon/footer strip should retain graph-switch behavior
3. Because the graph body looked up the nearest icon, a body tap near `17%` could inherit a temperature-home icon and switch to the temperature graph.

## Plan Chosen
1. Keep the bottom icon/footer strip behavior unchanged.
2. Change the cloud-cover graph body so every body zone always triggers zoom.
3. Add focused regression coverage for the cloud-cover body tap offset calculation instead of altering shared bottom-row routing helpers.

## Changes Implemented
1. Updated `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`.
2. Removed icon-dependent routing from `setupZoomTapZones(...)` in cloud-cover mode.
3. Made every `graph_hour_zone_*` body zone issue `ACTION_CYCLE_ZOOM`.
4. Preserved `HourlyBottomZoneHelper.setup(...)` unchanged so the bottom icon/footer zones still route by icon home.
5. Added debug logging in `CloudCoverViewHandler.setupZoomTapZones(...)`:
   1. widget id
   2. zone index
   3. resolved center offset
   4. zoom level
   5. current offset
   6. explicit `action=ZOOM`
6. Added a test helper in `CloudCoverViewHandler`:
   1. `resolveBodyTapZoneCenterOffset(...)`
   2. exposed as `@VisibleForTesting`
7. Extended `app/src/test/java/com/weatherwidget/widget/handlers/CloudCoverViewHandlerTest.kt` with new regression tests:
   1. clear temperature-home zone still resolves to zoom offset
   2. rainy zone still resolves to zoom offset
   3. narrow zoom preserves the expected offset mapping

## Verification
1. Ran focused unit tests:
   1. `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.handlers.CloudCoverViewHandlerTest --tests com.weatherwidget.widget.handlers.DayClickHelperTest`
   2. result: `BUILD SUCCESSFUL`
2. Confirmed existing bottom-row routing tests still passed in `DayClickHelperTest`.
3. Confirmed cloud-cover-specific tests still passed in `CloudCoverViewHandlerTest`, including pre-existing coverage for:
   1. smoothing iteration behavior
   2. cloud-cover source fallback
   3. empty hour-list cases
4. Verified live emulator behavior after install:
   1. cloud-cover widget instance was active
   2. logs showed every cloud-cover body zone bound with `action=ZOOM`
   3. a deliberate body tap on the `17%` label produced `ACTION_CYCLE_ZOOM`
   4. a post-tap screenshot confirmed the widget remained in cloud-cover view and zoomed into the narrower window
5. Also verified the boundary behavior was still correct:
   1. a tap in the bottom icon strip still switched to temperature
   2. this matches the intended split between body zones and bottom zones

## Commands Used
1. `rg -n "cloud cover|CloudCover|precipitation|temperature graph|zoom|tap|click|PendingIntent|fillInIntent|RemoteViews" app/src/main/java`
2. `sed -n '1,260p' app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
3. `sed -n '1,260p' app/src/main/java/com/weatherwidget/widget/handlers/WidgetRequestCodes.kt`
4. `sed -n '1,260p' app/src/main/java/com/weatherwidget/widget/handlers/HourlyBottomZoneHelper.kt`
5. `sed -n '1,240p' app/src/main/java/com/weatherwidget/widget/handlers/DayClickHelper.kt`
6. `sed -n '520,760p' app/src/main/res/layout/widget_weather.xml`
7. `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.handlers.CloudCoverViewHandlerTest --tests com.weatherwidget.widget.handlers.DayClickHelperTest`
8. `./gradlew installDebug`
9. `~/.Android/Sdk/platform-tools/adb devices`
10. `~/.Android/Sdk/platform-tools/adb -s emulator-5554 shell dumpsys appwidget`
11. `~/.Android/Sdk/platform-tools/adb -s emulator-5554 logcat -d -s WeatherWidgetProvider WidgetIntentRouter CloudCoverViewHandler TemperatureViewHandler`
12. `~/.Android/Sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p`
13. `~/.Android/Sdk/platform-tools/adb -s emulator-5554 shell input tap <x> <y>`

## Files Modified
1. `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
2. `app/src/test/java/com/weatherwidget/widget/handlers/CloudCoverViewHandlerTest.kt`
3. `session-logs/260405-cloud-cover-body-tap-zoom-fix.md`

## Outcome
1. The reported bug is fixed.
2. On the emulator, tapping the `17%` label area in the cloud-cover graph body now zooms the cloud-cover graph instead of switching to temperature.
3. Bottom icon-strip taps still switch graphs as designed.
