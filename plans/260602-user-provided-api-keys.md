# Plan: User-Provided API Keys

## Objective
Require users to input their own API keys for restricted/paid weather services (Tomorrow.io, Visual Crossing, WeatherAPI, Silurian, OpenWeatherMap) directly in the app, overriding or replacing the build-time `BuildConfig` keys to ensure production readiness and rate-limit safety.

## Changes
1. **Strings**: Add new strings to `res/values/strings.xml` for the API Keys section title, description, and placeholders.
2. **Layout**: 
    - Create `res/layout/item_api_key.xml` for a reusable row in the settings list (Label + EditText).
    - Modify `res/layout/activity_settings.xml` to include a container for these keys.
3. **Logic (`SettingsActivity.kt`)**:
    - Implement `setupApiKeysList()` to dynamically populate the API keys container.
    - Wire up saving of keys to `WidgetStateManager` whenever the text changes or is committed.
4. **API Clients**:
    - Audit `WeatherApi.kt`, `SilurianApi.kt`, `OpenWeatherMapApi.kt`, `VisualCrossingApi.kt`, and `TomorrowIoApi.kt`.
    - Ensure they consistently prioritize `widgetStateManager.getApiKey(source)` over `BuildConfig`.

## Verification
1. **Manual Audit**: Open Settings, enter a key, restart app, verify it persisted.
2. **Build**: Ensure `./gradlew assembleDebug` still passes.
3. **Log Check**: Verify that when a fetch occurs for a source with a custom key, it doesn't fail due to key extraction issues.
