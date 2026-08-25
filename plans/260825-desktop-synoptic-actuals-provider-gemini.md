# Implementation Plan: Fix Desktop Actuals Provider Selection Persistence

## Overview
When selecting Synoptic (or any non-default actuals provider) for a forecast-only source such as Silurian on the Desktop app (`ObservationsWindow`), the selection immediately reverts back to `METAR`. This plan fixes config merging so that `actualsProviders` changes made in `ObservationsWindow` are preserved, persisted, and dynamically reflected in the observations list and actuals blend.

---

## Root Cause Analysis

1. **The Issue**: In `ObservationsWindow.kt`, selecting an actuals source from the dropdown calls:
   ```kotlin
   onConfigUpdate(
       config.copy(
           settings = DesktopActualsPreference.withChoice(
               config.settings,
               currentSource,
               chosen.takeIf { it != ActualsProviderResolver.DEFAULT_PROVIDER },
           ),
       ),
   )
   ```
2. **The Culprit in `Main.kt` & `DesktopConfig.kt`**:
   `Main.kt` receives `onConfigUpdate` and delegates to `saveConfigAndNotify(newConfig, "observations-window")`.
   `saveConfigAndNotify` runs `mergeNonSettingsSave(...)` in `DesktopConfig.kt` for non-settings windows to prevent window movements from clobbering user settings.
   `mergeNonSettingsSave` had:
   ```kotlin
   val merged = draft.copy(settings = persisted.settings)
   return if (allowWeatherSourceChange) {
       merged.copy(settings = merged.settings.copy(weatherSource = draft.settings.weatherSource))
   } else {
       merged
   }
   ```
   `mergeNonSettingsSave` strictly overwrites the entire `settings` object with `persisted.settings`, discarding `draft.settings.actualsProviders`.
3. **The Result**:
   `configStore.save` writes back the persisted (empty/old) `actualsProviders`.
   `DesktopActualsPreference.update` is updated with the empty map.
   `ActualsProviderResolver.providerIdFor(SILURIAN)` falls back to `DEFAULT_PROVIDER` (`METAR`).
   The UI immediately snaps back to showing `METAR`.

---

## Proposed Changes

### 1. Update `mergeNonSettingsSave` in `DesktopConfig.kt`
- File: [`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt)
- Add `allowActualsProvidersChange: Boolean = false` parameter to `mergeNonSettingsSave`.
- If `allowActualsProvidersChange` is true, copy `draft.settings.actualsProviders` onto the merged settings.

```kotlin
internal fun mergeNonSettingsSave(
    persisted: DesktopConfig,
    draft: DesktopConfig,
    allowWeatherSourceChange: Boolean,
    allowActualsProvidersChange: Boolean = false,
): DesktopConfig {
    var settings = persisted.settings
    if (allowWeatherSourceChange) {
        settings = settings.copy(weatherSource = draft.settings.weatherSource)
    }
    if (allowActualsProvidersChange) {
        settings = settings.copy(actualsProviders = draft.settings.actualsProviders)
    }
    return draft.copy(settings = settings)
}
```

---

### 2. Update `saveConfigAndNotify` in `Main.kt`
- File: [`desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt)
- When calling `mergeNonSettingsSave`, pass `allowActualsProvidersChange = source == "observations-window" || source == "observations"`.

---

### 3. Immediate Observation Reload and Fetch in `ObservationsWindow.kt`
- File: [`desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt)
- When `onChoose` changes the provider:
  1. Emit `onConfigUpdate(...)` with the new settings choice.
  2. Reload observations for `currentSource` so the UI immediately switches to the selected provider's station list.
  3. If no cached observations exist for that provider, trigger `onRefreshData()` so the background service fetches them immediately.

---

### 4. Unit Testing
- File: [`desktop/src/test/kotlin/com/weatherwidget/desktop/SettingsDraftRebaseTest.kt`](file:///home/dcar/projects/weather-widget/desktop/src/test/kotlin/com/weatherwidget/desktop/SettingsDraftRebaseTest.kt)
- Add unit tests verifying:
  - `actualsProviders` passes through when `allowActualsProvidersChange = true`.
  - `actualsProviders` is preserved from `persisted` when `allowActualsProvidersChange = false`.

---

## Verification Plan

1. **Automated Tests**:
   - Run `./scripts/unit-tests.sh` to ensure all tests across `:shared`, `:desktop`, and `:app` pass.
2. **Desktop UI Verification**:
   - Rebuild and launch the desktop app (`./gradlew :desktop:run` or `scripts/buildStart.sh`).
   - Open Stations / Observations window on Desktop.
   - Switch source to Silurian.
   - Select **Synoptic** in the Actuals Source dropdown.
   - Verify that:
     - The dropdown persists and displays **Synoptic**.
     - Station observations display Synoptic stations.
     - Closing and reopening the Stations window maintains **Synoptic** as the selected actuals source.
