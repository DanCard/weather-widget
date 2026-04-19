# Plan: Increase Header Touch Zone Vertical Area (T-UI-05)

The user reported that on Samsung devices, touching the current temperature in the top-left of the Daily view often triggers history navigation instead of the expected view toggle. This is because the invisible `nav_left_zone` (40dp wide, full height) is declared later in the XML than the `current_temp_zone`, causing it to steal taps in the overlapping 40dp area at the top-left.

To fix this, we will increase the vertical hit area of all header elements to 96dp and move their definitions to the end of the root layout so they have the highest Z-order (click priority).

## Changes

### 1. Layout

#### `app/src/main/res/layout/widget_weather.xml`
- **Increase Heights**:
    - `current_temp_zone`: `72dp` -> `96dp`
    - `precip_touch_zone`: `40dp` -> `96dp` (also change `marginTop` from `4dp` to `-4dp` to align with current temp zone)
    - `home_touch_zone`: `48dp` -> `96dp`
    - `history_touch_zone`: `48dp` -> `96dp`
    - `current_stations_touch_zone`: `48dp` -> `96dp`
    - `settings_touch_zone`: `44dp` -> `96dp`
- **Reorder for Priority**:
    - Move all header touch zones (`current_temp_zone`, `precip_touch_zone`, `home_touch_zone`, `history_touch_zone`, `current_stations_touch_zone`, `api_touch_zone`, `settings_touch_zone`) to the end of the root `FrameLayout`.
    - They should be declared AFTER `nav_left_zone` and `nav_right_zone` so they win taps in the top 96dp of the widget.

## Verification Plan

### Automated Verification
- No automated tests can easily verify `RemoteViews` click priority and Z-order across OEM implementations.

### Manual Verification
1.  **Check Pixel Emulator**:
    - Verify that tapping the top-left (current temp) still toggles between Daily and Hourly views.
    - Verify that tapping the top-right (NWS/Open-Meteo) still toggles API sources.
    - Verify that tapping the top-most part of the left/right edges (above 96dp from top) triggers header actions, while tapping the middle/bottom of the same edges triggers navigation.
2.  **Check Samsung (if available)**:
    - Verify the specific reported issue is resolved: touching current temp no longer navigates to history.
