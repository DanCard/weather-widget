# Daily zoom-out header button setting and wider icon spacing on wide widgets

## Context & Request

**User Request (2026-10-16):**
> "I don't use the daily forecast zoom out view much. Please remove the zoom out icon on the header row. Also on wide widgets if the icons could be spaced out a bit more, that would be good. I sometimes accidently click a neighber by accident.
> Alternatively, I wonder if it would be smarter to have a setting for the zoom out icon in the header row, and only enable it , in that case."

### Key Observations
1. **Header Clutter & Accidental Clicks**: The recently added zoom-out (Months view) button (`ic_months_view`) created a 4th icon in the center group (`[Observations] [Home] [Months View] [History]`). When closely packed, clicking one frequently activates an adjacent button.
2. **Preference for a Setting**: Having a setting that defaults to **disabled (off)** gives the best of both worlds:
   - The user immediately gets a clean header row with no zoom-out icon by default.
   - Any user who wants quick access to Months view from the widget header can toggle it on in Settings.
   - Months view itself remains fully accessible through `MainActivity` (launcher entry) and `ForecastHistoryActivity` ("Months view" button).
3. **Wide Widget Icon Spacing**:
   - **Android Daily**: `dailyCenterIconZoneWidthDp(widthDp)` caps at **40dp** for all `widthDp >= 420`. On wide devices (450dp–600dp+), 40dp zones only leave ~20dp between 20dp glyph centers.
   - **Android Hourly**: Expands to 56dp at $\ge 450$dp, but drops abruptly to **24dp** between 420dp and 449dp.
   - **Desktop**: Daily header uses `(10 * scale).dp` and Hourly uses `(8 * scale).dp`, which is tight on wide windows.

---

## Proposed Design & Changes

### 1. Setting: Daily Zoom-Out Header Button (Default: Off)

Both Android and Desktop companion app will add a user preference, defaulted to `false`.

#### Android (`:app`)
- **`WidgetStateManager`**:
  - Add preference key `PREF_SHOW_DAILY_ZOOM_OUT_BUTTON = "show_daily_zoom_out_button"` (Boolean, default `false`).
  - Methods: `showDailyZoomOutButton(): Boolean` and `setShowDailyZoomOutButton(show: Boolean)`.
- **`DailyHeaderResolver`**:
  - Gate months button request by preference:
    ```kotlin
    wantMonths = useGraph && stateManager.showDailyZoomOutButton()
    ```
- **UI (`activity_settings.xml` & `SettingsActivity.kt`)**:
  - In `activity_settings.xml`, under the Daily View section (`today_overlay_title`), add a switch row:
    - Label: `R.string.daily_zoom_out_header_label` ("Show zoom-out button in header")
    - Description: `R.string.daily_zoom_out_header_description` ("Show a button in the daily forecast header to open Months view")
    - Switch ID: `@+id/daily_zoom_out_header_switch`
  - In `SettingsActivity.kt`:
    - Bind `daily_zoom_out_header_switch` to `widgetStateManager.showDailyZoomOutButton()`.
    - On change: save preference and call `repaintWidgets()`.

#### Desktop (`:desktop`)
- **`DesktopSettings` / `DesktopConfig`**:
  - Add `showDailyZoomOutButton: Boolean = false` to `DesktopSettings`.
- **`DesktopWidgetHeader.kt`**:
  - Change `val showMonthsButton = !config.monthsView` to:
    ```kotlin
    val showMonthsButton = !config.monthsView && config.settings.showDailyZoomOutButton
    ```
- **`SettingsWindow.kt`**:
  - Under `SettingsSection.TODAY_COLUMN` (Daily View section), add `TodayOverlayToggleRow`:
    - Label: "Show zoom-out button in header"
    - Tag: `daily_zoom_out_header_switch`
    - Binds to `currentConfig.settings.showDailyZoomOutButton`.

---

### 2. Wider Icon Spacing & Touch Zones on Wide Widgets

To eliminate accidental misclicks on adjacent buttons:

#### Android Daily View Touch Zones
- Update `HeaderConstants.kt` and `HeaderWidthChecker.dailyCenterIconZoneWidthDp`:
  - Introduce an extra-wide rung for `widthDp >= 450` (or `widthDp >= 460`):
    - `DAILY_CENTER_ICON_ZONE_EXTRA_WIDE_DP = 52f` (or `56f`, matching hourly).
    - `DAILY_EXTRA_WIDE_HEADER_MIN_WIDTH_DP = 450`.
  - Full ladder:
    - `widthDp >= 450`: **52dp** (comfortable air of 32dp between 20dp icons)
    - `420 <= widthDp < 450`: **40dp** (airy zone)
    - `360 <= widthDp < 420`: **32dp** (medium zone)
    - `< 360`: **24dp** (tight zone)
- Ensure date placement math (`HeaderWidthChecker.resolveDailyIconPlacementFromBounds` and `DailyForecastHeaderRenderer.resolveDateDrawX`) accounts for the wider slot without truncating or unnecessarily dropping the painted date.

#### Android Hourly View Touch Zones
- In `TemperatureTouchTargets.positionCenterIcons`:
  - When floating (`widthDp >= 420`):
    - `widthDp >= 450`: **56dp** (maintained)
    - `420 <= widthDp < 450`: **40dp** (increased from 24dp so it doesn't collapse on standard large phones like Pixel 7 Pro at 412–440dp).

#### Desktop Companion App Spacing
- In `DesktopWidgetHeader.kt`:
  - For wide headers / windows (or when uncrowded):
    - Increase Daily icon spacing from `(10 * scale).dp` to `(14 * scale).dp`.
    - Increase Hourly icon spacing from `(8 * scale).dp` to `(12 * scale).dp`.

---

## Verification Plan

### Automated Unit Tests
- **`:app` Unit Tests**:
  - `PositionDailyIconsRoboTest`: Verify zone width at 320dp, 350dp, 440dp, and $\ge 450$dp (assert 52dp on wide widgets).
  - `DailyIconLayoutLadderTest`: Verify layout decisions with `wantMonths` true vs false.
  - `HeaderWidthCheckerTest`: Test daily zone width ladder and date clearance on wide widths.
  - `WidgetStateManagerTest`: Verify getter/setter and default value for `showDailyZoomOutButton`.
- **`:desktop` Unit Tests**:
  - `DesktopUiTest`: Verify `open_months_view_daily` does not render when `showDailyZoomOutButton == false`, and renders when `true`.
  - `DesktopConfigTest`: Serialization of `showDailyZoomOutButton`.

### Build & Manual Verification
- Run JVM unit test suite: `./scripts/unit-tests.sh`
- Verify Android build: `./gradlew assembleDebug`
- Verify Desktop build: `./gradlew :desktop:compileKotlin`
