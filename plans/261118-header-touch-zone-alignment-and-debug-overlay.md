# Header Touch Zone Alignment & Debug Touch-Zone Overlay

## Problem

On the Pixel 7 Pro (`2A191FDH300PPW`, density 420 / 2.625x, widgets 78 [3-row DAILY], 88 [4-row DAILY], and 79 [1-row DAILY]), tapping the API source toggle in the top-right header frequently opens Settings instead.

## Evidence & Root Cause

Comparing the painted Daily header against the invisible XML touch zones laid over it:

1. **Painted Daily header** (`app/src/main/java/com/weatherwidget/widget/DailyForecastHeaderRenderer.kt`, lines 159–184; `HeaderConstants.kt`, lines 22–26 & 50):
   - Gear icon (`SETTINGS_ICON_SIZE_DP = 18dp`): drawn at `[width - 20dp, width - 2dp]`.
   - API source text (`Align.RIGHT`): right edge drawn at `width - (32 + 12)dp + 10dp = width - 34dp`.
   - Gap between the painted API label and the painted gear: `14dp` (`[width - 34dp, width - 20dp]`, midpoint `width - 27dp`).

2. **XML touch zones** (`app/src/main/res/layout/widget_weather.xml`, lines 1527–1572; hidden with `View.INVISIBLE` in `DailyGraphRenderer.kt` lines 316–328):
   - `top_right_header_container` has `layout_gravity="top|end"` and `layout_marginEnd="-2dp"` (right edge at `width + 2dp`).
   - `settings_touch_zone` has `layout_width="44dp"`, so it spans **`[width - 42dp, width + 2dp]`**.
   - `api_touch_zone` sits immediately to its left (`paddingStart="8dp"`, `paddingEnd="4dp"`), ending at **`width - 42dp`**.

3. **Resulting overlap**:
   - The rightmost `8dp` of the painted API label (`[width - 42dp, width - 34dp]`, ~1/3 of `"NWS"`) and the entire `14dp` gap between the API label and the gear sit **inside `settings_touch_zone`**.
   - In Hourly / XML-rendered header modes, `settings_touch_zone` (`44dp` wide with an `18dp` centered gear) also places `13dp` of invisible settings hit area to the left of the gear right up against `api_touch_zone`'s `4dp` `paddingEnd`, and shifts both the gear and API label ~9–12dp left relative to the Daily painted header.

## Proposed Changes

### 1. Debug-Only Touch-Zone Overlay (`adb` toggle)

Add a debug-build-only overlay following the existing `DebugLocationOverride` / `DebugSyncReceiver` pattern:

- **`app/src/main/java/com/weatherwidget/widget/DebugTouchZoneOverlay.kt`**:
  - Gated on `BuildConfig.DEBUG` (no-op / always `false` in release builds).
  - Reads/writes `debug_show_touch_zones` in `weather_prefs`.
  - When enabled, applies distinct semi-transparent background tints via `RemoteViews.setInt(id, "setBackgroundColor", color)` to header and navigation touch zones:
    - `api_touch_zone` / `text_mode_api_touch_zone`: semi-transparent green
    - `settings_touch_zone` / `text_mode_settings_touch_zone`: semi-transparent red
    - `current_temp_zone`: semi-transparent blue
    - `precip_touch_zone`: semi-transparent cyan
    - Center & inline header button zones: semi-transparent amber/purple
    - `nav_left_zone` / `nav_right_zone`: faint semi-transparent white
  - Invoked from `WidgetPushDispatcher` right before pushing `RemoteViews` (for full pushes; and if toggled off, a full repaint clears the backgrounds).
- **`app/src/debug/java/com/weatherwidget/debug/DebugTouchZoneReceiver.kt`** + **`app/src/debug/AndroidManifest.xml`**:
  - Gated by `android:permission="android.permission.DUMP"` and `src/debug` only.
  - Action `com.weatherwidget.debug.TOUCH_ZONES` with `--ez enabled true|false`, persisting the flag and triggering a full repaint of all widgets (UI-only, no network fetch).
- Document the `adb` hook in `arch/operations.md`.

### 2. Align `api_touch_zone` and `settings_touch_zone` in `widget_weather.xml`

Update `top_right_header_container` (`app/src/main/res/layout/widget_weather.xml`, lines 1527–1572) so the boundary between `api_touch_zone` and `settings_touch_zone` lands at the exact midpoint **`width - 27dp`** between the painted API label (`width - 34dp`) and the painted gear (`width - 20dp`), and so the XML-rendered Hourly header matches the painted Daily header horizontally:

- `settings_touch_zone`:
  - `layout_width="29dp"` (with container `layout_marginEnd="-2dp"`, spans **`[width - 27dp, width + 2dp]`** — `7dp` left of the gear, `2dp` right of the gear to widget edge).
  - `settings_icon`: `layout_gravity="center_vertical|end"`, `layout_marginEnd="4dp"` (spans `[width - 20dp, width - 2dp]`, matching `DailyForecastHeaderRenderer`'s `gearRight = width - 2dp`).
- `api_touch_zone`:
  - `layout_width="wrap_content"`, `paddingStart="10dp"`, `paddingEnd="7dp"` (right edge at **`width - 27dp`**; `api_source` right edge at `width - 27dp - 7dp = width - 34dp`, matching `DailyForecastHeaderRenderer`'s `apiX = width - 34dp`, with `7dp` of hit padding on the right and `10dp` on the left).

(When visually inspecting on the Pixel 7 Pro with the debug overlay enabled, we will also check the 1-row `text_mode_*` zones and adjust if needed.)

### 3. Tests

- Add a Robolectric geometry test verifying that at standard widget widths (and scaled headers), the painted Daily API label bounds fall completely inside `api_touch_zone` and never intersect `settings_touch_zone`, and that the painted gear icon falls completely inside `settings_touch_zone`.
- Add a unit/Robolectric test for `DebugTouchZoneOverlay` verifying tints apply when enabled and stay transparent when disabled.

## Verification

1. Build and install on the Pixel 7 Pro, toggle `com.weatherwidget.debug.TOUCH_ZONES --ez enabled true`, and visually verify with a screenshot that green (`api_touch_zone`) cleanly covers the API label and red (`settings_touch_zone`) cleanly covers the gear with no overlap on the API text (in both Daily and Hourly views, and check 1-row text mode).
2. Toggle `com.weatherwidget.debug.TOUCH_ZONES --ez enabled false` and verify all tints disappear cleanly.
3. Run `./scripts/staggered-tests.sh --install` (Android-only change; no desktop UI changes).
