# Metered Network, Background Data Access & Data Usage Tracking Plan

## Context & Problem Statement
On the Pixel 7 Pro, the widget displayed an error watermark pill:
`⚠ NWS UPDATES FAILING / Connection Refused · 7:58 PM`.
Investigation via `dumpsys netpolicy` revealed:
- UID 10406 was in `restrict-background-blacklist` (`policy=1 REJECT_METERED_BACKGROUND`, `blocked_state=APP_BACKGROUND|METERED_USER_RESTRICTED`).
- The phone was on cellular data (metered network).
- Android kernel/eBPF rejected all background network sockets from UID 10406 with `ECONNREFUSED`.
- The user desires the widget to have access to metered networks and not have its requests blocked.
- The user also requested:
  1. Tracking cellular and Wi-Fi data usage for both foreground and background.
  2. Displaying both cellular and Wi-Fi data usage for the past 24 hours, 7 days (week), 30 days (month), and 90 days at the bottom of the Settings screen.

User Scope Directives:
- **Skip settings shortcut**: Do not add warning banners/shortcuts to `SettingsActivity`.
- **Yes on error pill**: Tapping the error watermark pill on the widget should open the Android system screen for background/unrestricted data access.
- **Suggest at app first setup**: Suggest/prompt enabling unrestricted background data during initial app/widget setup (`ConfigActivity`).
- **Data Usage at Bottom of Settings**: Print both cellular and Wi-Fi data usage (foreground and background for past 24h, week, month, 90 days) at the bottom of the settings screen.

---

## Root Cause Analysis
1. **Android Network Policy Enforcement**:
   When an app is blacklisted from background data (`REJECT_METERED_BACKGROUND`) or when Android's system Data Saver is active without the app being whitelisted (`RESTRICT_BACKGROUND_STATUS_ENABLED`), any background network connection over metered (cellular) data fails immediately with `java.net.ConnectException: Failed to connect to <ip>:443 (Connection refused)`.
2. **Current App Behavior**:
   - `ForecastFetchCoordinator` and `CurrentTempRepository` catch `ConnectException` and classify it generically as `"CONN_REFUSED"`.
   - After 3 consecutive failures, `GraphFailureWatermarkRenderer` renders a watermark pill with "Connection Refused", giving the user no hint that OS data restrictions are causing the issue.
   - The watermark pill is drawn onto the bitmap canvas without a corresponding touch target, so tapping it does nothing.
   - The onboarding flow (`ConfigActivity`) requests location permissions, but never informs the user that background/unrestricted data is necessary for reliable updates on cellular networks.
   - The app does not display historical data usage figures in its UI to reassure the user about its network footprint.

---

## Proposed Solution

### 1. Watermark Pill Click Target (`widget_weather.xml` & View Handlers)
- Add a transparent touch target `R.id.error_pill_touch_zone` directly over the failure watermark pill area in `widget_weather.xml` (top-centered, 44dp height, horizontal bounds `marginStart="70dp"` and `marginEnd="90dp"` so it does not overlap navigation or header icons).
- In `DailyGraphRenderer`, `TemperatureViewBinder`, `PrecipViewHandler`, and `CloudCoverViewHandler`:
  - When `showErrorWatermark` is `true`:
    - Make `R.id.error_pill_touch_zone` `View.VISIBLE`.
    - Attach a `PendingIntent.getActivity` targeting `BackgroundDataResolutionActivity` (or `SettingsActivity` if the error code is `HTTP_401`/`HTTP_403`).
  - When `showErrorWatermark` is `false`:
    - Make `R.id.error_pill_touch_zone` `View.GONE`.

### 2. Resolution Trampoline (`BackgroundDataResolutionActivity.kt`)
- Create a lightweight activity `BackgroundDataResolutionActivity`:
  - When launched by tapping the error pill:
    - Launches `Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` with `data = Uri.parse("package:$packageName")`.
    - Falls back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` if the former is not handled on a specific OEM device.
  - On return (in `onResume`):
    - Checks if `restrictBackgroundStatus != RESTRICT_BACKGROUND_STATUS_ENABLED`.
    - If background data is now allowed or whitelisted: triggers an immediate expedited sync (`WeatherWidgetWorker.enqueueExpeditedSync(context)`) so the widget updates and clears the error pill right away.
    - Finishes itself.

### 3. First-Setup Onboarding Suggestion (`ConfigActivity.kt`)
- In `ConfigActivity.kt`:
  - Check if the app is already whitelisted: `ConnectivityManager.restrictBackgroundStatus == RESTRICT_BACKGROUND_STATUS_WHITELISTED`.
  - If not whitelisted and setup has not yet prompted (`KEY_BACKGROUND_DATA_PROMPTED` in prefs):
    - Present a friendly explanation dialog before or during setup completion:
      - Title: "Background Weather Updates"
      - Message: "To ensure weather forecasts update on mobile data and when Data Saver is active, allow unrestricted background data for Weather Widget."
      - Positive: "Settings" -> opens `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS`.
      - Negative: "Not Now" / "Skip" -> dismisses and finishes widget setup without blocking.
    - Mark `KEY_BACKGROUND_DATA_PROMPTED = true` so the user is never repeatedly bothered.

### 4. Precise Error Code Classification & Watermark Text
- In `ForecastFetchCoordinator.kt` and `CurrentTempRepository.kt`:
  - Helper `NetworkRestrictionHelper.isBackgroundDataRestricted(context)`:
    - Returns true if `cm.isActiveNetworkMetered` and `cm.restrictBackgroundStatus == RESTRICT_BACKGROUND_STATUS_ENABLED`.
  - When catching a `ConnectException` or `SocketException`, if `isBackgroundDataRestricted` is true, record `"DATA_RESTRICTED"` instead of generic `"CONN_REFUSED"`.
- In `GraphFailureWatermarkRenderer.kt`:
  - Add `"DATA_RESTRICTED"` -> `"Background Data Blocked"` (localized via `R.string.watermark_data_restricted`).

### 5. Data Usage Section (Cellular & Wi-Fi) at Bottom of Settings Screen
- Add catalogue entry in `SettingsSection.kt`:
  ```kotlin
  DATA_USAGE("Data Usage", platforms = setOf(Platform.ANDROID))
  ```
  *(Registered as Android-specific in the shared catalogue, maintaining parity checks across `:app` and `:desktop`).*
- Create `NetworkUsageTracker`:
  - Queries `NetworkStatsManager` for the calling UID (`Process.myUid()`):
    - `ConnectivityManager.TYPE_MOBILE` for Cellular usage
    - `ConnectivityManager.TYPE_WIFI` for Wi-Fi usage
  - Aggregates foreground (`Bucket.STATE_FOREGROUND`) and background (`Bucket.STATE_DEFAULT`) bytes for:
    - Past 24 Hours
    - Past 7 Days (Week)
    - Past 30 Days (Month)
    - Past 90 Days
- Add Card at bottom of `activity_settings.xml`:
  - Placed after "Support Development" (at the very bottom).
  - Contains title, description, and structured data rows showing both Cellular and Wi-Fi (Foreground, Background, and Total) for each time window.
- In `SettingsActivity.kt`:
  - Loads stats asynchronously in `lifecycleScope.launch(Dispatchers.IO)` and formats using standard binary units (KB/MB/GB).

---

## Verification Plan

### Automated Tests
1. **Unit / Robolectric Tests**:
   - `WatermarkLocalizationRoboTest`: Verify `"DATA_RESTRICTED"` resolves correctly to localized string.
   - `GraphFailureWatermarkRendererRobolectricTest`: Verify `"DATA_RESTRICTED"` produces readable text.
   - `LocaleResourceParityTest` & `HardcodedUserFacingStringTest`: Verify all new string resources meet localization and lint standards across all 20 locale folders.
   - `SettingsSectionOrderRoboTest` & `SettingsWindowSectionsTest`: Verify `SettingsSection` order is maintained across platforms.
   - `ConfigActivityRobolectricTest`: Verify disclosure dialog is prompted on first widget setup when not whitelisted and skipped when already prompted.
2. **Device / Empirical Verification**:
   - Run on Pixel 7 Pro (`2A191FDH300PPW`):
     - First-setup disclosure dialog shown in `ConfigActivity` when app is not whitelisted, with Settings and No Thanks options.
     - Tapping Settings opens OS "Mobile data & Wi-Fi" settings page for Weather Widget.
     - Bottom of Settings displays Data Usage card with Cellular and Wi-Fi (Foreground, Background, Total) for 24h, 7d, 30d, 90d.

---

## Verification Results

- **All Unit Tests Passed**: 4,157 tests passed across all modules and buckets in 55s (`./scripts/unit-tests.sh`):
  - 1,047 short tests passed
  - 26 localization tests passed (including `WatermarkLocalizationRoboTest` & `LocaleResourceParityTest`)
  - 66 medium tests passed
  - 1,062 long tests passed (including `ConfigActivityRobolectricTest`, `ConfigActivityAddFlowRoboTest`, `SettingsSectionOrderRoboTest`)
  - 1,573 shared tests passed
  - 383 desktop tests passed
- **On-Device Empirical Verification on Pixel 7 Pro (`2A191FDH300PPW`)**:
  - `SettingsActivity`: Verified Data Usage card appears at the bottom with accurate Cellular and Wi-Fi statistics across 24h, 7d, 30d, and 90d windows.
  - `ConfigActivity`: Verified "Background Weather Updates" disclosure dialog renders upon choosing a location when background data is not whitelisted. Tapping "Settings" launches `Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` directly to Weather Widget's "Mobile data & Wi-Fi" OS page.
  - `BackgroundDataResolutionActivity`: Verified exported trampoline activity launches OS background data settings and triggers a refresh when data is unrestricted.

