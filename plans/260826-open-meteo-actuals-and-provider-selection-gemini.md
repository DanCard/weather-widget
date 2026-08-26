# Open-Meteo Actuals Restoration and Alternative Actuals Provider Selection

## Summary

This plan restores Open-Meteo's ability to report temperature and cloud cover actuals (from its 15-minute / current / past-day reanalysis and model data) while integrating Open-Meteo into the newly established `ActualsProviderResolver` framework. This allows users to either use Open-Meteo's own actuals or select alternative actuals providers (such as physical METAR, Synoptic, or NWS station observations) on both Android and Desktop platforms.

---

## Background and Rationale

1. **Commit `4826fad2` (2026-08-21)** made Open-Meteo forecast-only (`historicalDataKind = NONE`, `supportsTemperatureActuals = false`, `supportsCloudActuals = false`) because Open-Meteo's Forecast API serves 15-minute model-interpolated/reanalysis data rather than direct physical station observations.
2. **Subsequent Infrastructure (2026-08-23 to 2026-08-26)** introduced:
   - `ActualsProviderResolver` (`:shared`) to decouple forecast providers from their actuals sources.
   - METAR and Synoptic actuals transports.
   - Per-source actuals provider pickers and persistence on both Android (`WeatherObservationsActivity`) and Desktop (`DesktopActualsPreference` / `ObservationsWindow`).
3. **The Solution**:
   - Reclassify Open-Meteo as having derived actuals (`HistoricalDataKind.RECENT_ANALYSIS` or `REANALYSIS_ARCHIVE`).
   - Restore Open-Meteo's sub-hourly (`minutely_15`), `current`, and `past_days` ingest for both temperature and cloud cover.
   - Treat Open-Meteo as a valid candidate provider in `ActualsProviderResolver` (`Tier.DERIVED`).
   - Allow Open-Meteo (when active as the display source) to have its actuals provider customized via the actuals provider picker, defaulting to its own actuals, giving users the best of both worlds.

---

## Detailed Architectural Design

### 1. `:shared` Module Changes

#### A. `com.weatherwidget.data.model.WeatherSource`
- Update `OPEN_METEO`:
  - `historicalDataKind = HistoricalDataKind.RECENT_ANALYSIS` (or `REANALYSIS_ARCHIVE`)
  - `supportsTemperatureActuals = true`
  - `supportsCloudActuals = true`
  - `supportsHistoricalActualsBackfill = true`

#### B. `com.weatherwidget.data.remote.OpenMeteoApi`
- Restore API request parameters for `minutely_15` variables (`temperature_2m,precipitation,weather_code,cloud_cover,cloud_cover_low`) and `current` (`temperature_2m,weather_code,cloud_cover,cloud_cover_low`).
- Restore `getCurrent(lat, lon)` method and `CurrentReading` data class.
- Restore `parseSubHourlyHistory()` parsing for 15-minute temperature and cloud cover actuals.

#### C. `com.weatherwidget.shared.observations.ActualsProviderResolver`
- Allow user preference selection for sources that borrow actuals (`!supportsTemperatureActuals`) **as well as** sources that have derived actuals or allow provider substitution (e.g. `OPEN_METEO`).
- Add `OPEN_METEO` to `candidates()` as `Tier.DERIVED`.
- When `OPEN_METEO` is the display source:
  - Default provider: `OPEN_METEO`.
  - If user picks `METAR` / `SYNOPTIC` / `NWS`, `providerIdFor(OPEN_METEO)` returns that provider's ID.

---

### 2. Android (`:app`) Platform Changes

#### A. Network & Ingest
- **`CurrentTempRepository.kt`**: Restore Open-Meteo handling in the current observation fetch path (`openMeteoApi.getCurrent(...)`).
- **`ForecastFetchCoordinator.kt`**: Ingest sub-hourly actuals from Open-Meteo's `RawFetch` into the `ObservationDao` under `api = "OPEN_METEO"`.
- **`DailyActualsStore.kt`**: Allow Open-Meteo daily actuals calculation/storage when Open-Meteo is the source or provider.

#### B. UI & Settings
- **`WeatherObservationsActivity.kt`**:
  - Update `updateActualsSourceRow()` to display the actuals provider selector for Open-Meteo in addition to borrowing sources.
  - In the chooser dialog, list `Open-Meteo (default, derived)`, `METAR (measured)`, `Synoptic (measured)`, `NWS (measured)`, etc.
  - When the user selects an alternative provider (e.g., METAR or Synoptic), persist it via `widgetStateManager.setActualsProvider(WeatherSource.OPEN_METEO, chosen)`.
- **Hourly Graph Handlers (`TemperatureStateResolver.kt`, `CloudCoverViewHandler.kt`)**:
  - Show the actuals provider annotation/label if the selected provider differs from Open-Meteo itself or indicates the active source.

---

### 3. Linux Desktop (`:desktop`) Platform Changes

#### A. Network & Service Ingest
- **`DesktopWeatherService.kt`**: Restore Open-Meteo in the `fetchObservations()` / `getCurrent()` pipeline and store them in the local database.
- **`DesktopWeatherRepository.kt`**: Ingest Open-Meteo sub-hourly and current readings as observation rows.

#### B. UI & Settings
- **`DesktopActualsPreference.kt`**: Ensure preferences for `OPEN_METEO` are saved and resolved.
- **`ObservationsWindow.kt` / `SettingsWindow.kt`**:
  - Display the actuals provider picker dropdown/button for Open-Meteo so desktop users can toggle between Open-Meteo's own actuals, METAR, Synoptic, etc.
- **`CloudCoverGraph.kt` / `HourlyTemperatureGraph.kt`**:
  - Reflect the chosen actuals provider for Open-Meteo on the desktop graphs.

---

## Dual-Platform Parity Checklist

- [ ] `:shared`: `WeatherSource.OPEN_METEO` capabilities updated.
- [ ] `:shared`: `OpenMeteoApi` restored with `current` and `minutely_15` support.
- [ ] `:shared`: `ActualsProviderResolver` accepts and resolves `OPEN_METEO` as candidate and allows user provider selection.
- [ ] `:app`: Observation ingest and current temp fetch restored for Open-Meteo.
- [ ] `:app`: `WeatherObservationsActivity` allows selecting actuals provider for Open-Meteo.
- [ ] `:desktop`: Observation ingest and current temp fetch restored for Open-Meteo.
- [ ] `:desktop`: Desktop settings/observations window allows selecting actuals provider for Open-Meteo.

---

## Verification Plan

### 1. Automated Tests
- **Shared unit tests**:
  - `./gradlew :shared:test`
  - Verify `WeatherSourceTest`, `ActualsProviderResolverTest`, `OpenMeteoApiTest`.
- **Android unit tests**:
  - `./gradlew :app:test`
- **Desktop unit tests**:
  - `./gradlew :desktop:test`

### 2. Manual / Empirical Verification
- **Desktop App**:
  - Run `./scripts/buildStart.sh`.
  - Select Open-Meteo as the display source.
  - Verify Open-Meteo's own actuals curve (temperature & cloud cover) is displayed.
  - Open Settings / Observations, change Open-Meteo's actuals provider to METAR or Synoptic, and verify the graph and observations switch to the selected provider.
- **Android Emulator**:
  - Run `./gradlew installDebug`.
  - Open the Weather Observations activity with Open-Meteo active.
  - Change actuals provider in the chooser dialog and verify the hourly graphs update accordingly.
