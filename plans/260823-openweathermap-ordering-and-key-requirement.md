# Implementation Plan: OpenWeatherMap Ordering, Fresh Install Default & API Key Requirement

## 1. Objective
1. Move **OpenWeatherMap** to the bottom of the configurable API sources list across both Android and Desktop.
2. Ensure OpenWeatherMap is **de-selected by default** on fresh installs (retaining NWS, Open-Meteo, and Silurian as the default active sources).
3. **Require an API key before enabling**: If a user attempts to enable OpenWeatherMap (or any key-requiring source lacking an API key), prevent the toggle, notify the user that an API key is required, and direct them to the API Key configuration section.

---

## 2. Proposed Changes

### A. `:shared` Module
- **`WeatherSourceOrdering.kt`**:
  - Update `ALL_CONFIGURABLE`:
    ```kotlin
    val ALL_CONFIGURABLE: List<WeatherSource> = listOf(
        WeatherSource.NWS,
        WeatherSource.OPEN_METEO,
        WeatherSource.SILURIAN,
        WeatherSource.TOMORROW_IO,
        WeatherSource.WEATHER_API,
        WeatherSource.OPEN_WEATHER_MAP,
    )
    ```
  - Update `DEFAULT_VISIBLE_IDS` to exclude `OPEN_WEATHER_MAP`:
    ```kotlin
    val DEFAULT_VISIBLE_IDS: List<String> = listOf(
        WeatherSource.NWS.id,
        WeatherSource.OPEN_METEO.id,
        WeatherSource.SILURIAN.id,
    )
    ```
- **`ApiKeySignupUrls.kt`**:
  - Ensure `sourcesRequiringKeys` lists `WeatherSource.OPEN_WEATHER_MAP` at the bottom:
    ```kotlin
    val sourcesRequiringKeys: List<WeatherSource> = listOf(
        WeatherSource.TOMORROW_IO,
        WeatherSource.SILURIAN,
        WeatherSource.WEATHER_API,
        WeatherSource.OPEN_WEATHER_MAP,
    )
    ```

---

### B. Android (`:app`) Module
- **`WidgetStateManager.kt`**:
  - Update `DEFAULT_VISIBLE_SOURCES` to only include default sources (NWS, Open-Meteo, Silurian; plus debug-only Tomorrow.io in DEBUG builds), excluding `OPEN_WEATHER_MAP`.
- **`SettingsActivity.kt`**:
  - In `rebuildSourceRows()`:
    When a user attempts to check an API source that requires an API key (e.g. `OPEN_WEATHER_MAP`, `WEATHER_API`, `TOMORROW_IO`) and `widgetStateManager.getApiKey(source).isNullOrBlank()` (and no built-in key exists):
      - Suppress the toggle and keep the checkbox unchecked (`checkbox.isChecked = false`).
      - Display a toast: `getString(R.string.api_key_required_to_enable, source.displayName)`.
      - Smoothly scroll the settings layout to the API Keys container (`findViewById<ScrollView>(R.id.settings_scroll_view).smoothScrollTo(...)`).
- **Resource Strings (`strings.xml` and 19 localized `values-*/strings.xml`)**:
  - Add string `api_key_required_to_enable`: `"%1$s requires an API key before enabling"`.

---

### C. Desktop (`:desktop`) Module
- **`SettingsWindow.kt`**:
  - In `ApiSourcesList`:
    Pass an `onRequiresApiKey: (WeatherSource) -> Unit` callback or check `apiKeys[source.id].isNullOrBlank()`.
    If attempting to enable a source requiring a key without a valid key present:
      - Refuse toggle.
      - Display a Snackbar message: `"${source.displayName} requires an API key before enabling."`

---

### D. Verification & Testing
1. **Unit Tests**:
   - `:shared`: Update `WeatherSourceOrderingTest.kt` and `ApiKeySignupUrlsTest.kt`.
   - `:desktop`: Update `DesktopConfigStoreTest.kt` and `SettingsWindowSectionsTest.kt`.
   - `:app`: Update `WidgetStateManagerTest.kt` and `LocaleResourceParityTest.kt`.
2. **Build & Execution**:
   - Run `./gradlew test` across all modules.
   - Run `./gradlew installDebug` and verify on emulator & devices.
   - Test toggling OpenWeatherMap without an API key (verifying warning toast/snackbar and refusal to enable) and with an API key (verifying enabling).
