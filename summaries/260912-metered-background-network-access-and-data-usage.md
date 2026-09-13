# Metered Background Network Access & Data Usage Tracking

*2026-09-12* — plan: `plans/260912-metered-background-network-access.md`

## Outcome

Resolved background network failures when the device is on metered cellular data or under Android Data Saver restrictions, and added comprehensive Cellular and Wi-Fi data usage tracking (24 hours, 7 days, 30 days, 90 days) to the bottom of the Settings screen.

1. **Metered Background Restriction Classification**:
   - Detected whether active network is metered and background data is blocked via `NetworkRestrictionHelper.isBackgroundDataRestricted(context)`.
   - Replaced generic `"CONN_REFUSED"` with precise `"DATA_RESTRICTED"` error tag in `ForecastFetchCoordinator` and `CurrentTempRepository`.
   - Rendered `"Background Data Blocked"` watermark pill on the widget canvas (localized across 20 languages).

2. **Error Pill Touch Target & Resolution Trampoline**:
   - Added transparent touch zone `R.id.error_pill_touch_zone` over the error watermark pill across Daily, Hourly (Temperature, Precipitation, and Cloud Cover) widget views.
   - Tapping the error pill launches `BackgroundDataResolutionActivity` (exported trampoline), which directs the user to `Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` for Weather Widget.
   - On return to the app (`onResume`), if restrictions were lifted, an immediate expedited sync (`WeatherWidgetWorker.enqueueExpeditedSync`) is dispatched to refresh forecasts and dismiss the watermark pill.

3. **First-Setup Onboarding Suggestion**:
   - During initial location configuration in `ConfigActivity`, if the app is not whitelisted for unrestricted background data (`RESTRICT_BACKGROUND_STATUS_WHITELISTED`) and has not yet been prompted (`KEY_BACKGROUND_DATA_PROMPTED`), an `AlertDialog` prompts the user with an explanation and direct link to system data settings ("Settings" / "Not Now").

4. **Network Data Usage Statistics**:
   - Created `NetworkUsageTracker` leveraging Android's `NetworkStatsManager` to query UID data consumption for `ConnectivityManager.TYPE_MOBILE` and `ConnectivityManager.TYPE_WIFI`.
   - Separates usage into Foreground (`Bucket.STATE_FOREGROUND`), Background (`Bucket.STATE_DEFAULT`), and Total across 24-hour, 7-day, 30-day, and 90-day windows.
   - Added a Data Usage card at the bottom of the Settings screen (`activity_settings.xml`) populated asynchronously via coroutines.
   - Registered `SettingsSection.DATA_USAGE` as an Android-platform section in `:shared` for cross-platform section parity.

---

## What Changed

### Core Implementation
- **`app/src/main/java/com/weatherwidget/util/NetworkRestrictionHelper.kt`** *(new)*: Checks `ConnectivityManager.isActiveNetworkMetered` and `restrictBackgroundStatus` to detect metered background data restriction states.
- **`app/src/main/java/com/weatherwidget/util/NetworkUsageTracker.kt`** *(new)*: Queries `NetworkStatsManager` for the app UID's historical cellular and Wi-Fi bytes (foreground, background, total) over 24h, 7d, 30d, and 90d intervals.
- **`app/src/main/java/com/weatherwidget/ui/BackgroundDataResolutionActivity.kt`** *(new)*: Lightweight trampoline activity that launches system unrestricted data settings and triggers an expedited sync upon return when unblocked.
- **`app/src/main/java/com/weatherwidget/widget/handlers/ErrorPillTouchTargetHelper.kt`** *(new)*: Shared helper managing visibility and PendingIntent binding for `R.id.error_pill_touch_zone`.
- **`app/src/main/java/com/weatherwidget/data/repository/ForecastFetchCoordinator.kt`** & **`CurrentTempRepository.kt`**: Tag connection/socket exceptions occurring under metered restrictions with `"DATA_RESTRICTED"`.
- **`app/src/main/java/com/weatherwidget/widget/GraphFailureWatermarkRenderer.kt`**: Added `"DATA_RESTRICTED"` mapping to `"Background Data Blocked"` (`R.string.watermark_data_restricted`).
- **`app/src/main/java/com/weatherwidget/ui/ConfigActivity.kt`**: Added first-setup background data disclosure dialog when saving the initial location.
- **`app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`**: Asynchronously loads and renders Cellular and Wi-Fi data usage metrics into the settings UI.
- **`shared/src/main/kotlin/com/weatherwidget/shared/settings/SettingsSection.kt`**: Registered `DATA_USAGE` with `platforms = setOf(Platform.ANDROID)`.

### Layout & Resources
- **`app/src/main/res/layout/widget_weather.xml`**: Added `R.id.error_pill_touch_zone` View positioned directly over the failure pill area.
- **`app/src/main/res/layout/activity_settings.xml`**: Added Data Usage card container and stats grid at the bottom of the scroll view.
- **`app/src/main/AndroidManifest.xml`**: Registered `BackgroundDataResolutionActivity` with `android:exported="true"`.
- **`app/src/main/res/values*/strings.xml`**: Added localized strings across all 20 locale folders for watermark text, onboarding dialog, and data usage labels.

### Tests
- **`GraphFailureWatermarkRendererRobolectricTest.kt`** & **`WatermarkLocalizationRoboTest.kt`**: Verified `"DATA_RESTRICTED"` formatting and localization.
- **`ConfigActivityRobolectricTest.kt`** & **`ConfigActivityAddFlowRoboTest.kt`**: Verified first-setup dialog display and dismissal behavior.
- **`HardcodedUserFacingStringTest.kt`**: Added `SettingsSection.DATA_USAGE.canonicalTitle` to `SHARED_PROSE_ALLOWLIST`.
- **`SettingsSectionTest.kt`**: Verified `SettingsSection` catalogue ordering and platform filtering.

---

## Insights & Technical Constraints

1. **Widget PendingIntent Target Export**:
   - `BackgroundDataResolutionActivity` must specify `android:exported="true"` in `AndroidManifest.xml` because `RemoteViews` click PendingIntents are fired from the Launcher process (system UID). Non-exported activities trigger a `SecurityException` at runtime when tapped from the home screen.
2. **Canonical Title Allowlist**:
   - `:app`'s `HardcodedUserFacingStringTest` validates that all user-facing strings originate from XML resources. Because `SettingsSection` in `:shared` defines canonical debug/logging titles, any new enum value must be included in `SHARED_PROSE_ALLOWLIST`.
3. **Robolectric Test Isolation**:
   - In `ConfigActivityAddFlowRoboTest`, tests simulate location selection flows without user interaction on alert dialogs. Setting `KEY_BACKGROUND_DATA_PROMPTED = true` in `setUp()` prevented the new background data disclosure dialog from intercepting test clicks intended for location confirmation.
4. **Parity Across Modules**:
   - Registering `SettingsSection.DATA_USAGE` with `platforms = setOf(Platform.ANDROID)` cleanly fulfilled cross-platform parity checks (`SettingsSectionOrderRoboTest` and `SettingsWindowSectionsTest`), keeping `:desktop` aligned without cluttering its UI.

---

## Verification

- **Automated Unit Tests**:
  - Ran `./scripts/unit-tests.sh`: all **4,157 / 4,157 unit tests passed** in 55s across Short, Medium, Long, Localization, Desktop, and Shared suites.
- **Empirical Device Verification on Pixel 7 Pro (`2A191FDH300PPW`)**:
  - Verified `SettingsActivity` loads and formats Cellular and Wi-Fi usage statistics correctly across 24h, 7d, 30d, and 90d windows.
  - Verified `ConfigActivity` displays the "Background Weather Updates" dialog on first setup when background data is restricted, and tapping "Settings" opens the device's "Mobile data & Wi-Fi" settings screen for Weather Widget.
  - Verified `BackgroundDataResolutionActivity` launches system settings without security exceptions when invoked via widget PendingIntent.
