# OpenWeatherMap: Free Tier 2.5 Implementation, Ordering, Fresh Install Defaults & API Key Requirement

## 1. Overview & User Requests
1. **Free 2.5 API Endpoints**: Replace One Call 3.0 (credit card requirement) with OpenWeatherMap's free `/data/2.5/weather` (current conditions) and `/data/2.5/forecast` (5-day, 3-hour forecasts with linear hourly interpolation and local timezone aggregation).
2. **List Ordering**: Place OpenWeatherMap at the bottom of the API data sources list.
3. **Fresh Install Default**: De-select OpenWeatherMap by default for new installs (retaining NWS, Open-Meteo, and Silurian as the active defaults).
4. **Require API Key Before Enabling**: Enforce that users must provide an API key before they can enable OpenWeatherMap (or other keyed sources without default keys). If a user attempts to enable it with an empty key, refuse the toggle, alert them with a notification/toast, and navigate to the API Keys section.

---

## 2. Key Architecture & Changes

### A. Shared Weather Logic & Ordering (`:shared`)
- **`OpenWeatherMapApi.kt`**:
  - Migrated from `/data/3.0/onecall` to concurrent calls to `/data/2.5/weather` and `/data/2.5/forecast`.
  - Maps 3-hour forecast slots to 1-hour intervals via linear temperature interpolation.
  - Aggregates daily highs, lows, and precipitation by local calendar days using the city timezone offset (`city.timezone`).
- **`WeatherSourceOrdering.kt`**:
  - Moved `WeatherSource.OPEN_WEATHER_MAP` to the bottom of `ALL_CONFIGURABLE` (`NWS`, `TOMORROW_IO`, `OPEN_METEO`, `SILURIAN`, `WEATHER_API`, `OPEN_WEATHER_MAP`).
  - Set `DEFAULT_VISIBLE_IDS = listOf("NWS", "OPEN_METEO", "SILURIAN")`.
- **`ApiKeySignupUrls.kt`**:
  - Reordered `sourcesRequiringKeys` to place `OPEN_WEATHER_MAP` at the bottom.
  - Added `requiresUserKey(source: WeatherSource): Boolean` helper to distinguish providers requiring user-entered keys from keyless or built-in providers (`Silurian`).

### B. Android (`:app`)
- **`WidgetStateManager.kt`**:
  - Set `DEFAULT_VISIBLE_SOURCES` to only include `NWS`, `OPEN_METEO`, and `SILURIAN` (plus debug-only `TOMORROW_IO` in `DEBUG` builds), ensuring OpenWeatherMap is de-selected by default on fresh installs.
- **`SettingsActivity.kt`**:
  - In `rebuildSourceRows`, when a user checks a disabled source that requires a key:
    - If `widgetStateManager.getApiKey(source)` is blank/empty, rejects the checkbox toggle (`checkbox.isChecked = false`).
    - Displays Toast: `getString(R.string.api_key_required_to_enable, source.displayName)`.
    - Smoothly scrolls `settings_scroll_view` to `api_keys_container`.
- **Localization**:
  - Added `api_key_required_to_enable` (`"%1$s requires an API key before enabling"`) across `app/src/main/res/values/strings.xml` and all 19 localized `values-*/strings.xml` files.

### C. Desktop Companion (`:desktop`)
- **`SettingsWindow.kt`**:
  - In `ApiSourcesList`, checks if an API key is present before enabling key-requiring sources.
  - Rejects checkbox toggle and shows a Snackbar message: `"${source.displayName} requires an API key before enabling."` if the key is missing.

---

## 3. Files Modified

- `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenWeatherMapApi.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherSourceOrdering.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/util/ApiKeySignupUrls.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherSourceDescriptions.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`
- `app/src/main/res/values/strings.xml` and 19 `app/src/main/res/values-*/strings.xml` files
- `desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt`
- Tests:
  - `shared/src/test/kotlin/com/weatherwidget/shared/util/WeatherSourceOrderingTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/shared/util/ApiKeySignupUrlsTest.kt`
  - `app/src/test/java/com/weatherwidget/data/remote/OpenWeatherMapApiTest.kt`
  - `app/src/test/java/com/weatherwidget/widget/WidgetStateManagerTest.kt`
  - `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopConfigStoreTest.kt`

---

## 4. Verification & Testing

1. **Automated Unit & Robolectric Tests**:
   - Ran `./gradlew test` across all modules: **2,064 tests passed with 0 failures**.
2. **Static Analysis & Lint**:
   - Ran `./gradlew ktlintCheck`: **0 violations**.
3. **Live Device / Emulator Verification**:
   - Installed debug APK to emulator (`emulator-5554`) and physical devices (`RFCT71FR9NT`, `2A191FDH300PPW`).
   - Verified OpenWeatherMap appears at the bottom of the API source list in Settings.
   - Verified tapping OpenWeatherMap without an API key refuses the check, triggers the Toast warning, and scrolls down to the API Key input row.
   - Verified entering an API key into the field allows OpenWeatherMap to be enabled and reordered normally.
   - Verified live OpenWeatherMap forecast fetch and rendering on home screen widget with current temperature and graph curves.
