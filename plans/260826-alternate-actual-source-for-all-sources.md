# Plan: Allow All Sources to Choose Alternate Actuals Source

## Summary
Provide the ability for all weather forecast sources (including NWS, Open-Meteo, Silurian, Tomorrow.io, WeatherAPI, Visual Crossing, and OpenWeatherMap) to configure an alternative actuals source (e.g. METAR, Synoptic, NWS, Open-Meteo, Tomorrow.io, WeatherAPI) across both Android (`:app`) and Linux Desktop (`:desktop`).

---

## Architectural Analysis & Current State

1. **Current Resolver Constraint**:
   - `ActualsProviderResolver.allowsAlternativeProvider(source)` currently returns `true` only for borrowing sources (`!supportsTemperatureActuals`, i.e. Silurian) and `OPEN_METEO`.
   - For all other sources (e.g. `NWS`, `TOMORROW_IO`, `WEATHER_API`), `allowsAlternativeProvider` returns `false`, causing `providerIdFor(source)` to strictly return `source.id` and hiding the actuals provider selector row in the UI.

2. **Observations List Filtering**:
   - `ObservationSourceMatcher.matchesStationsList` checked `ActualsProviderResolver.borrows(source)` before delegating to `matchesActualSource`. For non-borrowing sources using an alternate provider (e.g. NWS configured to Synoptic, or Open-Meteo configured to METAR), `matchesStationsList` would check `api == source.id` and fail to show the alternative provider's stations.

3. **Dual-Platform Parity**:
   - Both Android (`WeatherObservationsActivity`) and Linux Desktop (`ObservationsWindow` & `DesktopWeatherService`) consume `ActualsProviderResolver.allowsAlternativeProvider(source)` to display the selection UI and route observation fetches.

---

## Proposed Changes

### 1. `:shared` Module
- **`ActualsProviderResolver.kt`**:
  - Update `allowsAlternativeProvider(source: WeatherSource)`:
    ```kotlin
    fun allowsAlternativeProvider(source: WeatherSource): Boolean =
        source != WeatherSource.GENERIC_GAP && source != WeatherSource.METAR && source != WeatherSource.SYNOPTIC
    ```
  - Update `providerIdFor(source, preference)`:
    - Retain fallback logic: if a user preference is set and valid (`canProvide(it) && it != source`), return `chosen.id`. If no preference or default, return `source.id` if non-borrowing, or `DEFAULT_PROVIDER.id` (`METAR`) if borrowing.
  - Update comments and documentation.

- **`ObservationSourceMatcher.kt`**:
  - Update `matchesStationsList`:
    - Check if `providerId = ActualsProviderResolver.providerIdFor(source, actualsPreference)` differs from `source.id`.
    - If `providerId != source.id`, delegate to `matchesActualSource(stationId, api, source, allowGenericGap = false, actualsPreference)`.
    - If `providerId == source.id`, match `api == source.id && matchesObservationSource(stationId, WeatherSource.fromId(providerId))`.
  - Update `matchesActualSource`:
    - Ensure synthetic rows are filtered when `provider` is `NWS` or `TOMORROW_IO`.

- **`WeatherSource.kt`**:
  - Mark `VISUAL_CROSSING` and `OPEN_WEATHER_MAP` with `supportsTemperatureActuals = false` and `supportsCloudActuals = false` so that they borrow METAR by default (since they lack station observation feeds) while allowing full alternate provider configuration.

- **Shared Tests (`ActualsProviderResolverTest.kt`)**:
  - Update tests to verify NWS, Tomorrow.io, WeatherAPI, and Silurian can all have alternate providers configured via preferences.
  - Verify default behavior remains intact when no preference is set.

---

### 2. Android Platform (`:app`)
- **`WeatherObservationsActivity.kt`**:
  - `updateActualsSourceRow()` automatically shows the actuals provider row for all sources.
  - In `refreshData()`, when `providerId != currentSource.id`, if `providerId` is not METAR or Synoptic (e.g. NWS, Open-Meteo, Tomorrow.io), trigger `weatherRepository.refreshCurrentTemperature` for `WeatherSource.fromId(providerId)` to refresh observations from the chosen provider.

---

### 3. Linux Desktop Platform (`:desktop`)
- **`ObservationsWindow.kt`**:
  - The `ActualsSourceRow` is displayed and selectable for all sources in the Observations tab.
- **`DesktopWeatherService.kt`**:
  - In `fetchObservationsOnly()`, when `provider != source.id`, dispatch the observation fetch to the selected provider's fetch method (`fetchBorrowedObservationsOnly`, `fetchNwsObservationsOnly`, `fetchTomorrowIoObservationsOnly`, `fetchOpenMeteoObservationsOnly`, etc.).
- **Desktop Tests (`DesktopActualsPreferenceTest.kt`)**:
  - Add test cases verifying NWS and Tomorrow.io store and resolve alternative actuals provider choices.

---

## Verification Plan

### Runtime regression found on Samsung
- Widget `345` displayed Tomorrow.io forecasts with Synoptic selected as its actuals provider. The
  Synoptic fetch and database were healthy (3,432 matching rows across 11 stations), but
  `ActualTemperatureSeriesBuilder` reported `rawObs=3432 filteredObs=0 emitted=0` and drew no actual
  temperature line.
- Root cause: Tomorrow.io's two-product normalization was gated by the forecast/display source. It
  therefore passed borrowed Synoptic rows into `TomorrowIoActuals.forTemperatureSeries`, which
  accepts only Tomorrow.io provenance station IDs and removed every row.
- Fix the gate to use the resolved actuals provider. This preserves Synoptic/METAR/NWS rows selected
  for a Tomorrow.io forecast and also normalizes Tomorrow.io realtime/history rows when another
  forecast source selects Tomorrow.io as its actuals provider.
- Add shared regression tests covering both provider directions, then verify the repaired pink line
  on Samsung widget `345` with a screenshot and `TEMP_ACTUALS_PERF` evidence.

### Automated Tests:
- Run `./gradlew :shared:test` to verify `ActualsProviderResolverTest` and `ObservationSourceMatcherTest`.
- Run `./gradlew :desktop:test` to verify desktop preferences and observation matching.
- Run `./gradlew :app:test` to verify Android test suite.

### Empirical Verification:
- Build and launch desktop app (`./scripts/buildStart.sh`):
  - Select NWS, open Observations window -> verify Actuals source row is visible (shows "NWS (default, measured)").
  - Change actuals source to Synoptic or METAR -> verify stations list and graph actuals curve update to the chosen provider.
  - Select Tomorrow.io or Silurian -> verify actuals source chooser works seamlessly.
- Install on Android emulator (`./gradlew installDebug`):
  - Open Weather Observations activity -> verify actuals source row is visible and selectable for NWS, Open-Meteo, Tomorrow.io, Silurian.
