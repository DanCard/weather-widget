# Remove Google Weather special priority and ordering rules

## Summary of Changes

Removed the special primary-on-enable, default visible priority, and automated migration rules that previously favored Google Weather over other weather sources:

1. **Removed `PRIMARY_ON_ENABLE` & Focus Hijack**:
   * In [`WeatherSourceOrdering.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherSourceOrdering.kt), `PRIMARY_ON_ENABLE` is set to `emptySet()`.
   * Enabling Google Weather now appends it to the end of the enabled list like any other source and preserves the currently displayed source on active widgets and desktop popups instead of automatically switching focus.

2. **Aligned Debug Build Visible Source Defaults**:
   * In [`DefaultVisibleSources.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/DefaultVisibleSources.kt) and [`WidgetStateManager.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt), debug builds no longer default to Google Weather as primary when a key is baked. Debug defaults are now `[NWS, OPEN_METEO, SILURIAN, TOMORROW_IO]` and release defaults remain `[NWS, OPEN_METEO, SILURIAN]`.

3. **Removed Automatic Google Primary Migration**:
   * In [`WeatherSourcePreferences.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WeatherSourcePreferences.kt), removed `migrateGoogleWeatherEnabledIfNeeded()`. Existing installs will no longer be migrated to place Google Weather first or force widgets to switch to it.

4. **Updated Documentation & Tests**:
   * Updated [`arch/operations.md`](file:///home/dcar/projects/weather-widget/arch/operations.md) to document standard appending behavior on enable and removed special Google debug defaults.
   * Updated [`WeatherSourceOrderingTest.kt`](file:///home/dcar/projects/weather-widget/shared/src/test/kotlin/com/weatherwidget/shared/util/WeatherSourceOrderingTest.kt), [`DefaultVisibleSourcesTest.kt`](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/DefaultVisibleSourcesTest.kt), [`GoogleWeatherPrimaryIntegrationTest.kt`](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/GoogleWeatherPrimaryIntegrationTest.kt), [`SettingsSourceListRoboTest.kt`](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/ui/SettingsSourceListRoboTest.kt), and [`WidgetStateManagerTest.kt`](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/WidgetStateManagerTest.kt).

## Verification

* `./scripts/unit-tests.sh`: All 6,419 tests passed across `:shared`, `:desktop`, and `:app` (short, medium, long, and localization test buckets).
* `./scripts/emulator-tests.sh -c com.weatherwidget.widget.WidgetStateManagerMigrationInstrumentedTest`: 4/4 passed on `emulator-5554`.
* `./gradlew assembleDebug`: Built debug APK successfully.
* `./gradlew :desktop:createDistributable`: Built desktop distributable successfully.
