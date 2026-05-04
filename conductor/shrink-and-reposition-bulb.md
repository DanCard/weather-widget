# Plan - Shrink Header Touch Zones to Prevent Home Button Blockage

The home button in the hourly temperature graph header has stopped working on some widget sizes because the "near-miss" touch zones for the temperature and API source are too large (96dp height, up to 112dp width), causing them to overlap and intercept taps intended for the center icons (Home, History, Stations).

## Proposed Changes

### 1. Layout Adjustments
Modify `app/src/main/res/layout/widget_weather.xml` to shrink the header touch zones:

- **Reduce Height**: Change height from `96dp` to `48dp` for all header touch zones:
    - `current_temp_zone`
    - `precip_touch_zone`
    - `home_touch_zone`
    - `history_touch_zone`
    - `weather_stations_touch_zone`
    - `api_touch_zone`
    - `settings_touch_zone`
- **Reduce Width**:
    - `current_temp_zone`: Reduce from `96dp` to `84dp`.
    - `api_touch_zone`: Reduce from `112dp` to `80dp`.
- **Align Margins**: Ensure margins don't push zones into the center area.

## Verification Plan

### Automated Tests
- No existing automated tests cover touch zone overlap in XML, but I will check if there are any related unit tests in `TemperatureTouchTargetsTest.kt` or similar.

### Manual Verification
1. **Visual Audit**: Check the layout in the Android Studio preview (if possible) or by running on the emulator.
2. **Emulator Test**:
    - Create a narrow widget (e.g., 2 or 3 columns).
    - Switch to Hourly Temperature view.
    - Verify that tapping the Home icon correctly switches back to the Daily view.
    - Verify that tapping the API source still toggles the API.
    - Verify that tapping the temperature still toggles the view mode.
