# Implement: cloud-actuals review findings (plans/260821-review-cloud-actuals-feature-arc.md)

**Date:** 2026-08-21
**Status:** COMPLETE — all items implemented; verification below all passing.

Implements the "Recommended order of work" from the review, in order. Out of scope: A4 (desktop
null→0 vs Android omit — pre-existing, changes axis geometry), which stays documented.

## Verification (all passing)

- `./gradlew :shared:testShortShared`
- `./gradlew :app:testShortDebugUnitTest --tests CloudCoverViewHandlerTest --tests HourlyObservationBackfillLocationTest`
- `./gradlew :app:testDebugUnitTest --tests NwsCloudActualsRoundTripTest`
- `./gradlew :desktop:testShortDesktop`

## Changes

1. **A2 + B1 — Android through `CloudSeriesBuilder`; stop dropping `cloudCoverLow`**
   - `HourlyForecast.toEntity()` maps `cloudCoverLow` (was dropped).
   - `GraphDataLoader` history-row mapping maps `cloudCoverLow`.
   - `HourlyForecastStitcher` coalesces `cloudCoverLow` in `stitch` and `collapse` (live row
     missing low now inherits it from history/same-site rows, matching desktop's raw-SQL read).
   - `CloudCoverViewHandler.buildCloudHourDataList` calls `CloudSeriesBuilder.build` instead of
     re-implementing the pairing; presentation (label/icon/sun) still decorated locally from the
     per-hour entity. Text mode prefers low too.

2. **A1 — live curve draws the visible (low-preferred) layer on both platforms**
   - Desktop `CloudCoverGraph`: `rawCloudValues` = `(cloudCoverLow ?: cloudCover) ?: 0f`.
   - Android: covered by (1) — `CloudHourData.cloudCover` becomes the series `forecastCover`
     (low for live/future, frozen for past), so a low-only row now draws at all.
   - NWS residual (total forecast vs low actual) stays; noted where the curves pair.

3. **A3 + B4 — one shared rule for which sources have a cloud actual curve**
   - `HistoricalDataKind.preservesHistoricalCloud` (explicit, mirrors precip today).
   - `WeatherSource.supportsCloudActuals` = NWS || kind.preservesHistoricalCloud.
   - `HistoricalActualsBackfill` uses the explicit cloud gate, one `fromId` lookup.
   - Android handler gate becomes the shared property (Silurian/Tomorrow.io/WeatherAPI now
     render their already-written synthetic actuals, matching desktop).

4. **B2.1 — `MetarCloudBlender.fromSiteRows`**: the NWS-blend vs synthetic-pin branch shared;
   both DAOs (`ObservationDao`, `DesktopWeatherDao`) delegate.

5. **B2.2 — shared NWS observation mapping**: `NwsObservationMapper` in `:shared`
   (unit conversion, blank-name fallback, hardened timestamp parse, haversine distance,
   METAR-low rule). Android `NwsObservationSource.toEntity` and desktop
   `DesktopWeatherService.toReading` become thin wrappers. Note: Android distances switch from
   `Location.distanceBetween` (geodesic) to haversine — sub-0.3% change, feeds IDW weights only.

6. **C1 — prior-run rows store low-layer values in `cloudCoverLow`**
   - Writers (`ForecastFetchCoordinator`, `DesktopWeatherDao.upsertSyntheticCloudSeries`) write
     `cloudCoverLow`, leave `cloudCover` null.
   - Readers (`getSyntheticCloudSeries` both platforms) prefer `cloudCoverLow ?: cloudCover`, so
     pre-change rows keep reading until the hourly REPLACE-upsert rewrites them. No migration.

7. **B3 — `CloudHourBucket`** (shared) owns the round-to-nearest-hour rule; blender (3 sites)
   and `metarCloudGapReason` use it.

8. **B2.3/B2.4 — `CloudCoverGraphPalette`** (shared): curve/label/fill ARGB constants +
   `ACTUAL_LABEL_MIN_DIVERGENCE`; Android style + desktop composable consume it.

9. **B2.5 — `CloudWatermarkPlacement.candidateCenters`** (shared, pure): the duplicated
   emptiest-window search; both renderers keep only their own placement/draw loop.

10. **B5 — delete dead `smoothingIterationsFor`** (never called by `updateWidget`; no other
    handler uses an iterations-1 NARROW rule, so wiring it would be an unverifiable render
    change). Tests removed with it.

## Tests

- Shared: `CloudSeriesBuilderTest` — future hour with only a low value draws low; low-only live
  row is included (not dropped).
- Shared: `MetarCloudBlenderTest` — `fromSiteRows` pins the synthetic station (low-preferred) and
  delegates NWS to the blend.
- Shared: new `WeatherSourceCloudActualsTest` — the supported-source set.
- Shared: new `NwsObservationMapperTest` — F conversion, METAR→low wiring, hardened timestamp.
- App: `CloudCoverViewHandlerTest` — low-only rows render; future hours draw low when total null.
- Existing suites re-run: `:shared:testShortShared` (whole module), app touched classes,
  `:desktop` compile + short tests.

## Verification

Covered by the passing runs listed at the top; the per-command list below was the plan of record.

- `./gradlew :shared:testShortShared`
- `./gradlew :app:testShortDebugUnitTest --tests "com.weatherwidget.widget.handlers.CloudCoverViewHandlerTest" --tests "com.weatherwidget.widget.handlers.HourlyObservationBackfillLocationTest"`
- `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.data.repository.NwsCloudActualsRoundTripTest"`
- `./gradlew :desktop:testShortDesktop` + `:desktop:compileKotlin`
- Full `:app` short bucket if time allows.
