# Move Location Settings to the Top of Settings Screen

**Date**: 2026-09-15  
**Goal**: Move the "Default Location" / change location settings section to the top of the Settings screen on both Android (`:app`) and Linux Desktop (`:desktop`), maintaining cross-platform parity and contract tests.

---

## 1. Current State & Empirical Analysis

- On Android (`app/src/main/res/layout/activity_settings.xml`), the "Default Location" section (`location_settings_section`, lines 444–490) is currently positioned after "Weather Data Sources" and before "Icon gallery".
- On Desktop (`desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt`), the `DEFAULT_LOCATION` `SettingsCard` (lines 329–360) is similarly placed below "Weather Data Sources" and above "Icon gallery".
- Cross-platform ordering is strictly enforced by the shared catalogue `com.weatherwidget.shared.settings.SettingsSection` (`shared/src/main/kotlin/com/weatherwidget/shared/settings/SettingsSection.kt`):
  - Declaration order in `SettingsSection` is defined as the canonical on-screen order.
  - `SettingsSectionOrderRoboTest` in `:app` verifies that Android's section headers (tagged with `android:tag="settings_section"`) strictly match `SettingsSection.forPlatform(Platform.ANDROID).map { it.title }`.
  - `SettingsWindowSectionsTest` in `:desktop` verifies that Compose `SettingsCard` vertical positions strictly follow `SettingsSection.forPlatform(Platform.DESKTOP).map { it.title }`.
  - `SettingsSectionTest` in `:shared` validates catalogue completeness and platform filtering.

---

## 2. Proposed Changes

### 2.1 Shared Catalogue (`:shared`)
File: `shared/src/main/kotlin/com/weatherwidget/shared/settings/SettingsSection.kt`
- Move `DEFAULT_LOCATION("Default Location")` to the first position in the `SettingsSection` enum, immediately preceding `HOURLY_ZOOM("Hourly Zoom")`.

### 2.2 Android Layout (`:app`)
File: `app/src/main/res/layout/activity_settings.xml`
- Move the `location_settings_section` `LinearLayout` (including its header `TextView` with `android:tag="settings_section"` and `bg_surface_card` containing `current_location_label` and `set_location_button`) from lines 444–490 to the top of the content `LinearLayout` right below the header `FlexboxLayout` (before the Hourly Zoom section).
- Adjust margins so spacing is consistent with the rest of the settings sections:
  - Section header `layout_marginTop="16dp"` / `layout_marginBottom="8dp"`.
  - Card bottom margin removed or normalized to match other cards (`layout_marginBottom="0dp"` with following section providing `layout_marginTop="24dp"`).

### 2.3 Desktop Settings (`:desktop`)
File: `desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt`
- Move `SettingsCard(title = SettingsSection.DEFAULT_LOCATION.title) { ... }` to be the first card in the scrollable `Column`, positioned immediately above `SettingsCard(title = SettingsSection.HOURLY_ZOOM.title)`.

---

## 3. Verification Plan

1. **Unit & Contract Tests**:
   - `:shared`: Run `./gradlew :shared:test --tests "com.weatherwidget.shared.settings.SettingsSectionTest"` to ensure shared catalogue invariants hold.
   - `:app`: Run `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.ui.SettingsSectionOrderRoboTest"` to verify Android layout order matches `SettingsSection`.
   - `:desktop`: Run `./gradlew :desktop:test --tests "com.weatherwidget.desktop.SettingsWindowSectionsTest"` to verify Desktop layout order matches `SettingsSection`.
   - Run `SettingsActivityRobolectricTest` to confirm location label and button interactions continue functioning properly.

2. **On-Device / Visual Verification**:
   - Run `./gradlew installDebug` to install to `emulator-5554`.
   - Launch `com.weatherwidget/.ui.SettingsActivity`.
   - Capture a screenshot via `adb exec-out screencap -p` and inspect it to ensure "Default Location" is prominently visible at the top without scrolling.
