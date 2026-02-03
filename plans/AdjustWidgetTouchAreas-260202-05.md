# Plan: Adjust Widget Touch Areas

## Problem
The current temperature button is hard to tap because its touch area matches only the visible text size (`wrap_content`). Meanwhile, navigation arrows have generous 48dp touch zones.

## Current Touch Areas

| Element | Visual Size | Touch Size | Notes |
|---------|-------------|------------|-------|
| Current Temp | ~50dp (text) | ~50dp | No touch zone overlay |
| Nav Arrows | 20dp | **48dp** | Has invisible overlay |
| API Indicator | ~20dp text | 64dp × 48dp | Large container |
| Settings | Content area | Entire center | Falls through from other elements |

## Solution

### 1. Add Touch Zone Overlay for Current Temperature
Create an invisible `FrameLayout` overlay around the current temp area (similar to nav arrow approach).

**File:** `app/src/main/res/layout/widget_weather.xml`
- Add a `current_temp_zone` FrameLayout (80dp × 56dp) positioned at top-left
- Keep existing `current_temp` TextView visually unchanged
- Wire both to the same PendingIntent

**File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- Update `setupCurrentTempToggle()` to also set click on `R.id.current_temp_zone`

### 2. Reduce Navigation Touch Zones
Reduce `nav_left_zone` and `nav_right_zone` from 48dp to 32dp width.

**File:** `app/src/main/res/layout/widget_weather.xml`
- Change `android:layout_width="48dp"` to `32dp` for both zones

### 3. Settings Area
The settings tap is handled by the content area (`text_container`, `graph_view`). This naturally occupies space not claimed by other touch zones. By enlarging the current temp zone, it will automatically take space from the settings area.

## Files to Modify

1. `app/src/main/res/layout/widget_weather.xml`
   - Add `current_temp_zone` overlay (~80dp × 56dp)
   - Reduce nav zone widths from 48dp to 32dp

2. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
   - Add `setOnClickPendingIntent(R.id.current_temp_zone, ...)` in `setupCurrentTempToggle()`

## Verification
1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Test tapping current temperature - should toggle between daily/hourly view
3. Test navigation arrows still work but have slightly smaller touch target
4. Verify visual layout is unchanged
