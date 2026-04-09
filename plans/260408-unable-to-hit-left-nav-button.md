# Fix: Left Nav Arrow Touch Target Intercepted by Temperature Zone

## Context

The left navigation arrow on the daily display widget is untappable. Every tap in that area triggers the temperature graph toggle instead of navigating back. This is a **Z-order bug** in the root `FrameLayout` of `widget_weather.xml`, not a size issue.

### Root Cause

In Android's `FrameLayout`, children declared **later** in XML draw on top and receive touches first. The current declaration order:

| Line | View | Width | Position |
|------|------|-------|----------|
| 14 | `nav_left` (ImageButton) | **20dp** | start, center_vertical |
| 888 | `nav_left_zone` (invisible overlay) | **40dp** | start, full height |
| 908 | `current_temp_zone` (invisible overlay) | **96dp x 72dp** | top, start |

`current_temp_zone` is declared last, so it sits **on top of** `nav_left_zone` and intercepts all taps in the top-left 96x72dp area. It's bound to `ACTION_TOGGLE_VIEW` (switches to temperature graph) via `HeaderTapTargetHelper.bindToggleTemperatureHeader()`.

The `nav_left_zone` at 40dp is entirely within the 96dp-wide `current_temp_zone`, so the nav touch zone **never receives touches** in the header region.

## Plan

### Step 1: Fix Z-order in `widget_weather.xml`

Move `nav_left`, `nav_right`, `nav_left_zone`, and `nav_right_zone` to the **very end** of the root `FrameLayout` (after `settings_touch_zone`, before `</FrameLayout>`). This makes them the topmost views in the touch hierarchy.

### Step 2: Widen the `nav_left` and `nav_right` ImageButtons (20dp -> 40dp)

- Change `android:layout_width` from `20dp` to `40dp`
- Add `android:paddingStart="10dp"` and `android:paddingEnd="10dp"` to preserve the visual arrow position
- Keep `android:scaleType="centerInside"` so the icon stays centered in the visible area

This doubles the button's own clickable area as a second layer of defense, while the transparent padding keeps the arrow visually identical.

### Step 3: Update tests in `NavTouchZoneRoboTest.kt`

Add assertions verifying `nav_left` and `nav_right` have 40dp width (currently only tests the zone overlays).

## Files to Modify

1. `app/src/main/res/layout/widget_weather.xml` — relocate nav views to end, widen buttons
2. `app/src/test/java/com/weatherwidget/widget/NavTouchZoneRoboTest.kt` — add button width assertions

## Verification

1. `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.NavTouchZoneRoboTest"` — unit tests pass
2. `./gradlew installDebug` — deploy to device/emulator
3. On the daily display widget, tap the left arrow — should navigate back (not switch to temperature graph)
4. Visual check — arrow icons should look identical to before (no size/position change)
5. Verify temperature header tap still works when tapping the current temp text (not near the arrow)
