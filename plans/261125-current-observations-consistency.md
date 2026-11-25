# Plan: Current Observations Screen Consistency Fixes

**Date:** 2026-11-25
**Scope:** `:shared`, `:app`, `:desktop`

## Context & Verified Evidence

Captured from the live desktop Current Observations window (`Meteo` / `Actuals source: NWS`, 2026-11-25) and verified in code across `:shared`, `:app`, and `:desktop`:

1. **`STALE` and `NONE_FOUND` subtitles name the display source instead of the actuals provider.**
   - In `StationsList.englishSubtitle` (`shared/.../StationsList.kt`), `CurrentObservationsWindow.kt`, and `CurrentObservationsActivity.kt`, both `Subtitle.NONE_FOUND` and `Subtitle.STALE` interpolate `currentSource.displayName` (e.g. `"Open-Meteo"`).
   - When a source borrows actuals (e.g. Open-Meteo or Silurian with `Actuals source: NWS` or `METAR`), `StationsList.load` queries the borrowed provider's stations (`ObservationSourceMatcher.matchesStationsList`), not the display source's. Saying `"No recent observations found for Open-Meteo."` or `"No recent Open-Meteo observations — showing the latest, 2d ago."` misidentifies the feed that is actually empty or stale.

2. **METAR-derived rows (`OFFICIAL (Web)` and `api=METAR`) have an empty `condition`.**
   - In the live screenshot, `KGEU` (`OFFICIAL (Web)`) displays raw METAR `KGEU 251748Z 04006KT 10SM CLR 20/00 A3008` yet has a blank condition label next to `68.0°`, while sibling API stations (`KSDL`, `KDVT`, `KFFZ`) display `Clear`.
   - Root cause: `MetarObservationMapper.toReading` (`shared/.../MetarObservationMapper.kt:54-57`) hardcodes `condition = ""` because the METAR JSON/text feeds do not ship a `textDescription` field. `MetarDecoder.decode` already extracts both `presentWeather` (`RA`, `FG`, `BR`, `SN`, `TS`, etc.) and `skyLayers` (`CLR`, `SKC`, `FEW`, `SCT`, `BKN`, `OVC`, `VV`).

3. **Minor Android vs. Desktop Observations-tab layout/styling drift.**
   - **Desktop `Actuals source` row typography:** `ActualsSourceRow` in `CurrentObservationsWindow.kt:634-645` renders both label and value at `13sp` regular, whereas the station cards directly below use `18sp–32sp` (and Android's `activity_current_observations.xml` renders `actuals_source_value` larger and bold relative to the label).
   - **Subtitle scrolling behavior:** On Android (`activity_current_observations.xml:225-238`), `R.id.subtitle` is pinned above the `RecyclerView`. On desktop (`CurrentObservationsWindow.kt:682-692`), the `STALE` subtitle is an `item` inside `LazyColumn` so it scrolls away with the cards.

---

## Proposed Changes

### 1. Name the actuals provider in `STALE` / `NONE_FOUND` subtitles (`:shared`, `:app`, `:desktop`)

- Add a helper on `StationsList` (e.g., `StationsList.actualsDisplayName(source: WeatherSource, preference: ...)` or resolve via `ActualsProviderResolver.providerIdFor(source)` → `WeatherSource.fromId(providerId).displayName`) so both platforms pass the same resolved actuals provider name into the `STALE` and `NONE_FOUND` subtitles.
  - When a source uses its own observations (`providerId == source.id`), the label is unchanged (`source.displayName`).
  - When a source borrows (`providerId != source.id`, e.g., Open-Meteo → NWS or Silurian → METAR), the subtitle names the actuals provider (e.g., `"No recent NWS observations — showing the latest, 2d ago."` / `"No recent observations found for METAR."`).
- Update `CurrentObservationsWindow.kt` (desktop) and `CurrentObservationsActivity.kt` (Android) to use this resolved display name.
- Note: No new string resources or locale changes are needed — the existing `obs_subtitle_none_found` and `obs_subtitle_stale` format strings already take `%1$s`.

### 2. Derive `condition` from decoded METAR in `:shared`

- Add a pure-Kotlin formatter in `:shared` (`MetarConditionFormatter.format(report: MetarDecoder.MetarReport?, layers: List<NwsApi.CloudLayer>): String`):
  1. **Present weather takes priority** when `report.presentWeather` is non-empty: map standard METAR intensity/descriptor/phenomena tokens (e.g., `-RA` → `"Light Rain"`, `+TSRA` → `"Heavy Thunderstorm & Rain"`, `FG` → `"Fog"`, `BR` → `"Mist"`, `HZ` → `"Haze"`, `SN` → `"Snow"`, `DZ` → `"Drizzle"`, `SHRA` → `"Rain Showers"`). Join multiple distinct phenomena cleanly (e.g. `"Light Rain, Mist"`).
  2. **Sky cover fallback** when `presentWeather` is empty and `layers` is non-empty: pick the highest coverage layer (`OVC` / `VV` → `"Overcast"`, `BKN` → `"Mostly Cloudy"`, `SCT` → `"Partly Cloudy"`, `FEW` → `"Few Clouds"`, `CLR` / `SKC` / `NCD` / `CAVOK` → `"Clear"`).
  3. **Empty string** when neither present weather nor sky layers are reported (never invent `"Clear"` from silence, preserving `NwsObservationMapperCloudTest` invariants).
- Wire `MetarConditionFormatter.format(decoded, layers)` into `MetarObservationMapper.toReading` so both `api=METAR` rows and NWS `OFFICIAL (Web)` fallback rows (`NwsMetarTextWeb`, `NwsMetarHistoryWeb`, `NwsGapFillRun`) populate `condition`.
- Also check `NwsObservationMapper.toReading`: when NWS API returns a blank `textDescription` alongside a non-blank `rawMessage` / cloud layers, fall back to `MetarConditionFormatter` rather than storing `""`.
- Confirm no downstream consumer (fog, rain-now, cloud blender) breaks — `ObservedMorningFog` and `RainNow` already prefer `rawMetar` over `condition`, and `MetarCloudBlender` reads `cloudCover*` fields, not `condition`.

### 3. Align Desktop `ActualsSourceRow` styling and subtitle pinning (`:desktop`)

- In `CurrentObservationsWindow.kt`:
  - Scale `ActualsSourceRow` typography to match the desktop Observations tab hierarchy (e.g., `16sp` secondary label and `16sp` bold accent value, matching the `18sp` tabs and `21sp` card titles).
  - Pin the non-empty `STALE` subtitle above `LazyColumn` (inside the `Observations` `Column`, right below `ActualsSourceRow`) so it stays visible while scrolling, matching Android's `activity_current_observations.xml` layout. Keep the centered empty-state message when `observations.isEmpty()`.

---

## Verification & Test Plan

1. **Unit tests (`:shared`):**
   - `MetarConditionFormatterTest`: cover `CLR`/`SKC`/`FEW`/`SCT`/`BKN`/`OVC`/`VV`, present-weather combinations (`-RA`, `+TSRA`, `FG`, `BR`, `HZ`, `SN`, `SHRA`), present-weather precedence over sky cover, and empty/unreported input returning `""`.
   - `MetarObservationMapperTest`: verify `condition` is populated from `rawOb`/`cloudLayers` (e.g. `KGEU 251748Z ... CLR ...` → `"Clear"`) and stays `""` when unreported.
   - `StationsListTest`: verify `actualsDisplayName` returns the borrowed provider's display name for borrowing sources and the source's own display name otherwise.
2. **Integration tests (`:app` Robolectric & `:desktop` SQLite/Compose):**
   - `CurrentObservationsActivityRobolectricTest`: verify `STALE` and `NONE_FOUND` subtitles name the active actuals provider when a borrowing source has stale or missing observations.
   - `DesktopStationsListTest` / desktop Compose test: verify the pinned `STALE` subtitle and `ActualsSourceRow` rendering.
3. **End-of-task gate & live verification:**
   - Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
   - Capture an updated screenshot of the desktop Current Observations window and confirm `KGEU` (or any web METAR card) shows its decoded condition, `Actuals source` typography is balanced, and `STALE`/`NONE_FOUND` subtitles name the actuals provider.
