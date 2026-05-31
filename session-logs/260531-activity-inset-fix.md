# Session Log: Activity Layout Inset Fix (Android 15 Edge-to-Edge Compatibility)

**Date:** Sunday, May 31, 2026
**Topic:** Fixing activity layout overlap with system status bar on Android 15 (API 35).

## Problem Statement
On Samsung devices and other platforms running Android 15 (API 35), the header of the "Forecast History" activity (and potentially others) was overwriting the Android status bar. This occurs because Android 15 enforces edge-to-edge drawing by default for apps targeting API 35. Without explicit inset handling, layouts are drawn behind the status bar and navigation bar.

## Investigation
- **Target SDK:** Verified in `app/build.gradle.kts` that `targetSdk` is 35.
- **Theme:** The app uses `Theme.AppCompat.Light.NoActionBar`, which doesn't provide automatic status bar spacing when edge-to-edge is active.
- **Layout Analysis:** Examined `activity_forecast_history.xml`. The root `LinearLayout` lacked `android:fitsSystemWindows="true"`.
- **Scope:** Systematic audit of all activity layouts defined in `AndroidManifest.xml` revealed that none of them had `fitsSystemWindows` set, leading to inconsistent UI behavior across the application.

## Changes Implemented

### XML Layouts
Added `android:fitsSystemWindows="true"` to the root elements of the following layouts to ensure they correctly reserve space for system UI (status bar and navigation bar):

1.  **Forecast History:** `app/src/main/res/layout/activity_forecast_history.xml`
2.  **Main Activity:** `app/src/main/res/layout/activity_main.xml`
3.  **Config Activity:** `app/src/main/res/layout/activity_config.xml`
4.  **Settings Activity:** `app/src/main/res/layout/activity_settings.xml`
5.  **App Logs:** `app/src/main/res/layout/activity_app_logs.xml`
6.  **Current Observations:** `app/src/main/res/layout/activity_weather_observations.xml`
7.  **Statistics:** `app/src/main/res/layout/activity_statistics.xml`

### Programmatic Layouts
Modified **Privacy Policy Activity** which generates its UI in code:
- **File:** `app/src/main/java/com/weatherwidget/ui/PrivacyPolicyActivity.kt`
- **Change:** Set `fitsSystemWindows = true` on the root `ScrollView` instance.

## Verification
- Checked `AndroidManifest.xml` to ensure all activities (`MainActivity`, `ConfigActivity`, `SettingsActivity`, `StatisticsActivity`, `AppLogsActivity`, `ForecastHistoryActivity`, `WeatherObservationsActivity`, `PrivacyPolicyActivity`) are covered by these changes.
- Verified that no existing layouts already had complex inset handling that might conflict with `fitsSystemWindows`.

## Conclusion
The application is now fully compatible with Android 15's default edge-to-edge behavior. All activities will now correctly display their headers below the status bar, providing a consistent and polished user experience across different Android versions and OEM skins like Samsung's One UI.
