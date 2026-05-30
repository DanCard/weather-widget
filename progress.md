# Progress Log

## Session: 2026-05-29

### Changes Made

**Build Configuration:**
- `compileSdk` 34 → 35
- `targetSdk` 34 → 35
- Robolectric 4.12.1 → 4.14.1 (SDK 35 support)
- Removed unused Glide dependency

**Code Fixes (from other agent's revert):**
- Restored missing `WidgetStateManager` imports in `WeatherApi.kt`, `SilurianApi.kt`, `OpenWeatherMapApi.kt`, `TomorrowIoApi.kt`
- Fixed DI providers in `AppModule.kt` to pass `widgetStateManager` to API constructors
- Added `PrivacyPolicyActivity` to AndroidManifest.xml
- Added missing string resources: `privacy_policy_title`, `privacy_policy_body`

**Test Fixes:**
- Updated `RobolectricTest` base class: SDK 34 → 35
- Updated 66 test files: `@Config(sdk = [34])` → `@Config(sdk = [35])`
- Updated `OpenWeatherMapApiTest` to use mock `WidgetStateManager` instead of `apiKey` param
- Updated `SilurianApiTest` to pass mock `WidgetStateManager` to constructor
- Updated `TomorrowIoApiTest` to pass mock `WidgetStateManager` with API key stub
- Updated `WeatherApiTest` to use mock `WidgetStateManager` with API key stub

**Documentation:**
- Removed non-existent `FeatureTourActivity` from `AGENTS.md`
- Created `plans/playstore-launch-checklist.md` with all user action items

### Verification
- `./gradlew assembleDebug` — BUILD SUCCESSFUL
- `./gradlew testByDurationDebugUnitTest` — BUILD SUCCESSFUL (all tests pass)
- Release build compiles but needs signing credentials (`RELEASE_STORE_PASSWORD` in `local.properties`)
