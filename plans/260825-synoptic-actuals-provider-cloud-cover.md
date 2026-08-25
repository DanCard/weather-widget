# Synoptic Actuals Provider and Cloud Cover Support — Implementation Plan

**Date:** 2026-08-25  
**Status:** Approved. Ready for implementation.  

---

## 1. Goal & Overview

Enable `WeatherSource.SYNOPTIC` to function as a complete **actuals provider** for forecast-only sources (such as `Silurian` and `Open-Meteo`) across both Android (`:app`) and Linux Desktop (`:desktop`), including:
1. Fetching and persisting observations under `api = "SYNOPTIC"`.
2. Parsing cloud cover layers from both `metar_set_1` and Synoptic's `cloud_layer_1_set_1d` (or `weather_summary_set_1d`).
3. Drawing the solid actual cloud cover curve on the widget in `CLOUD_COVER` view mode.
4. Displaying Synoptic stations and cloud readings in `WeatherObservationsActivity` / `ObservationsWindow`.

---

## 2. Root-Cause Analysis

### Observed Symptoms
- On emulator-5554, when viewing `Silurian` in `CLOUD_COVER` view mode with `Actuals source: Synoptic`, no solid actual curve renders to the left of the `NOW` marker (only the dashed forecast line appears).
- In `WeatherObservationsActivity`, selecting `Actuals source: Synoptic` results in *"No recent observations found for Silurian."*
- Database query on `weather_database` shows **0** rows with `api = 'SYNOPTIC'`.

### Root Cause
1. `WeatherSource.SYNOPTIC` was defined as an actuals provider in `WeatherSource.kt` and allowed in `ActualsProviderResolver.candidates()`.
2. When a borrowing source selects `SYNOPTIC`, `MetarCloudBlender` and `ObservationSourceMatcher` look for stored observations with `api = "SYNOPTIC"`.
3. However, no pipeline or refresher exists to fetch and store Synoptic observations as an actuals provider:
   - `SynopticApi` only has a single-station fetch (`fetchSynopticObservations`) used for the NWS web fallback (which files under `api = "NWS"` with `isWebFallback = true`).
   - `SynopticApi.parseRadiusTimeseries` was defined in `SynopticApi.kt` but lacks a network method `fetchRadiusTimeseries(...)`.
   - `parseStationObservations` only parses cloud layers if `metar_set_1` is present; `cloud_layer_1_set_1d` is ignored.
   - There is no `SynopticObservationFetcher` in `:shared`, no `SynopticObservationSource`/`SynopticObservationRefresher` in `:app`, and no Synoptic branch in `:desktop`'s `DesktopWeatherService`.

---

## 3. Architecture & Implementation Plan

### Phase 1: Shared Layer (`:shared`)

1. **`SynopticApi.kt`**:
   - Add `fetchRadiusTimeseries`:
     ```kotlin
     suspend fun fetchRadiusTimeseries(
         latitude: Double,
         longitude: Double,
         radiusMiles: Double = 25.0,
         recentMinutes: Long = 120,
     ): FetchOutcome<List<RadiusStation>>
     ```
     Targeting `https://api.synopticdata.com/v2/stations/timeseries?radius=<lat>,<lon>,<radiusMiles>&recent=<recentMinutes>&token=<token>&obtimezone=utc&qc=on&qc_checks=all&qc_flags=on`.
   - Update `parseStationObservations`:
     - If `rawMetar` is absent, fall back to parsing `cloud_layer_1_set_1d` objects (`sky_condition` and `height_agl`) into `NwsApi.CloudLayer`:
       - `"overcast"` -> `"OVC"`
       - `"broken"` -> `"BKN"`
       - `"scattered"` -> `"SCT"`
       - `"few"` -> `"FEW"`
       - `"clear"` / `"fair"` / `"none"` -> `"CLR"`
       - `"obscured"` / `"vertical visibility"` -> `"VV"`
     - If both `metar_set_1` and `cloud_layer_1_set_1d` are absent but `summary` is a known sky condition (e.g. `"overcast"`, `"broken"`), create a height-less `CloudLayer`.

2. **`NwsObservationMapper.kt`**:
   - Add parameter `api: String = "NWS"` to `toReading(...)` so observations can be mapped under `api = WeatherSource.SYNOPTIC.id`.

3. **`SynopticObservationFetcher.kt` (New in `:shared`)**:
   - Implement `SynopticObservationFetcher` (mirroring `MetarObservationFetcher`):
     - Takes `SynopticApi` and logging sink.
     - Implements `fetchObservationsResult(latitude, longitude, radiusMiles, hours, limit)` calling `api.fetchRadiusTimeseries(...)` and mapping `RadiusStation` observations to `ObservationReading(api = "SYNOPTIC")`.

4. **`SynopticFetchPolicy.kt` (New in `:shared`)**:
   - Evaluates whether Synoptic actuals should be fetched this cycle:
     - `PRIMARY`: Any active/displayed widget is a borrowing source whose actuals provider is `SYNOPTIC`.
     - `NON_PRIMARY`: A non-displayed visible widget is configured with `SYNOPTIC`.
     - `NONE`: No visible widget borrows from Synoptic.

---

### Phase 2: Android App Layer (`:app`)

1. **`SynopticObservationSource.kt` (New in `:app`)**:
   - Wraps `SynopticObservationFetcher`.
   - Maps `ObservationReading` -> Room's `ObservationEntity`.
   - Injected via Hilt in `AppModule.kt`.

2. **`SynopticObservationRefresher.kt` (New in `:app`)**:
   - Checks `SynopticFetchPolicy.tierFor(visibleSources, activeDisplaySources, actualsPreference)`.
   - Executes `synopticObservationSource.fetchObservations(...)` and inserts rows into `observationDao`.
   - Logs `SYNOPTIC_OBS_STORED`.

3. **`WeatherWidgetWorker.kt` & `FullSyncPipeline.kt`**:
   - Wire `SynopticObservationRefresher` alongside `MetarObservationRefresher` during full sync and background cadence loops.

4. **`WeatherObservationsActivity.kt`**:
   - When switching or viewing an actuals source of `SYNOPTIC`, trigger an on-demand refresh if no rows exist in the recent window.

5. **`CloudCoverViewHandler.kt`**:
   - In `maybeRefreshCloudWhileViewing`, trigger Synoptic refresh when `cloudProvider == WeatherSource.SYNOPTIC`.

---

### Phase 3: Desktop App Layer (`:desktop`)

1. **`DesktopWeatherService.kt`**:
   - Inject `synopticFetcher = SynopticObservationFetcher(synopticApi) { tag, message, level -> Log.i(tag, message) }`.
   - Update `fetchBorrowedObservationsOnly`:
     - When `provider == WeatherSource.SYNOPTIC.id`, fetch from `synopticFetcher.fetchObservations(latitude, longitude, hours = hours)`.

2. **`DesktopWeatherRepository.kt`**:
   - Update `fetchBorrowedMetarRecovery` (rename/generalize to `fetchBorrowedRecovery`) to also fetch recovery rows when `ActualsProviderResolver.providerIdFor(displaySource) == WeatherSource.SYNOPTIC.id`.

---

### Phase 4: Unit Testing & Verification

1. **Unit Tests**:
   - `SynopticApiRadiusTest` in `:shared`: verify radius timeseries parsing, QC handling, and cloud layer parsing from `cloud_layer_1_set_1d`.
   - `SynopticObservationFetcherTest` in `:shared`: verify end-to-end mapping to `ObservationReading(api = "SYNOPTIC")`.
   - `BorrowedCloudActualsTest` in `:shared`: verify `cloudFor(WeatherSource.SILURIAN, synopticRows)` produces correct cloud percentages from Synoptic rows.
   - Run `./scripts/unit-tests.sh` to ensure all 3,595+ tests pass with duration categories intact.

2. **On-Device / Emulator Verification**:
   - Install to emulator-5554 (`./gradlew installDebug`).
   - Trigger refresh on widget with Silurian + Synoptic actuals.
   - Query emulator database: verify `observations` table has rows with `api = 'SYNOPTIC'` and non-null `cloudCoverLow`.
   - Verify emulator screenshot: solid actual cloud cover curve renders on the widget.
   - Verify `WeatherObservationsActivity`: station list displays Synoptic stations with temperatures and cloud coverage.
