# Plan: Hourly Graph Bottom Zone Diagnostics

## Goal
Add semi-transparent diagnostic colors and explicit `clickable="true"` XML attributes to the hourly graph bottom touch zones to diagnose why clicks on the footer area are not triggering.

## Proposed Changes

### 1. Update `app/src/main/res/layout/widget_weather.xml`
For each touch zone in the hourly graph bottom row, add explicit click attributes and a semi-transparent background color for debugging.

#### Hour Body Overlay Zones (`graph_bottom_hour_zone_0` to `12`)
- Add `android:clickable="true"`
- Add `android:focusable="true"`
- Change `android:background="@android:color/transparent"` to `android:background="#440000FF"` (semi-transparent Blue)

#### Hour Footer Zones (`graph_bottom_hour_footer_zone_0` to `12`)
- Add `android:clickable="true"`
- Add `android:focusable="true"`
- Change `android:background="@android:color/transparent"` to `android:background="#44FF0000"` (semi-transparent Red)

## Verification
1. Rebuild and install the widget.
2. Toggle to an hourly graph (e.g. Temperature).
3. Observe if red (footer) and blue (overlay) boxes appear.
4. Verify if clicking the red boxes (at the very bottom) now triggers navigation or zoom.
5. If the red boxes are not visible, it indicates a layout issue (e.g. they are pushed out of bounds).
