# Deriving mid/high cloud actuals from METAR-layer sources

**Date:** 2026-08-27
**Status:** Proposed — analysis complete, awaiting decision

## Goal

Decide whether — and how — to populate mid (`cloudCoverMid`) and high (`cloudCoverHigh`) cloud
actuals from the observation/METAR-layer sources (Synoptic, NWS observations, Aviation Weather),
alongside the Open-Meteo forecast bands added in 68ef68af.

## Findings (evidence)

### Two paths, one source of banded cloud

- **Forecast** `HourlyForecast.cloudCover{Low,Mid,High}`: only **Open-Meteo** has banded cloud
  (`cloud_cover_mid` / `cloud_cover_high`). NWS (`skyCover`), Tomorrow.io (`cloudCover` +
  `cloudBase`/`cloudCeiling`), Silurian (`cloud_cover`), OpenWeatherMap (`clouds.all`),
  WeatherAPI.com (`cloud`), and Visual Crossing (`cloudcover`) all report **total column only**.
  Synoptic has no forecast product at all.
- **Actuals** `ObservationReading.cloudCover{Low}`: derived from METAR-style cloud **layers**
  (`amount` + `baseMeters`). Synoptic supplies `cloud_layer_1_set_1d` (`sky_condition` +
  `height_agl`) and/or raw METAR; NWS supplies `cloudLayers`; Aviation Weather supplies `clouds`.
  `MetarSkyCover` currently derives only `totalPercent` and `lowPercent` (base <
  `LOW_LAYER_CEILING_M = 2000 m`); the two mappers store `cloudCover = null` and
  `cloudCoverLow = lowPercent(layers)`. There are **no** mid/high columns on the actuals path.

### The ceilometer ceiling makes "high" unobservable

`MetarSkyCover` documents that an automated ceilometer sees nothing above ~12,000 ft (~3.7 km).
METAR `base` values therefore saturate around ~3.6 km. Against Open-Meteo's band definitions
(low <3 km, mid 3–8 km, high >8 km):

| Band | METAR-observable? |
|---|---|
| Low (<3 km) | ✅ fully |
| Mid (3–8 km) | ⚠️ only the ~3–3.6 km slice (bases above that are unreported) |
| High (>8 km) | ❌ never — cirrus is invisible to the ceilometer |

So even with correct bucketing, METAR-derived **mid** would be thin/partial and **high** would be
effectively always null. Only Open-Meteo (a model/reanalysis product) can see high cloud.

### Threshold alignment (separate pre-existing quirk)

`MetarSkyCover.LOW_LAYER_CEILING_M = 2000 m` (≈6,500 ft aviation convention) does **not** match
Open-Meteo's low/mid boundary of 3 km. The existing `cloudCoverLow` actuals therefore already use a
different low cutoff than the forecast `cloud_cover_low`. Any mid/high work should align all three
cutoffs to 3 km / 8 km so actuals and forecast bands are comparable — or explicitly document the
divergence.

## Recommendation

**Do not build METAR-derived high actuals** — the sensor cannot see that band, so the field would be
permanently null and would only mislead.

**Mid actuals are possible but low-value** — the observable slice is 3–3.6 km, so the band would
rarely exceed a couple of tens of percent and would be empty above ~12,000 ft. If parity with the
forecast mid band is ever wanted, it is the only band worth the effort.

Net: the mid/high story stays forecast-side (Open-Meteo). If the team still wants mid actuals, the
outline below is the minimal path; high is explicitly out of scope.

## Proposed implementation (mid only, if approved)

1. **`MetarSkyCover`** — add `midPercent(layers)`: max amount among layers with
   `baseMeters in 3_000..8_000` (plus the null-base rule already used by `lowPercent`, for
   consistency with how unknown heights are handled). Optionally reconcile
   `LOW_LAYER_CEILING_M` 2000 → 3000 to match Open-Meteo (small MAE risk, per the existing comment).
2. **Model** — add nullable `cloudCoverMid` to `ObservationReading` (keep `cloudCoverHigh` out).
3. **Mappers** — `NwsObservationMapper` / `MetarObservationMapper` set
   `cloudCoverMid = MetarSkyCover.midPercent(layers)`.
4. **Persistence** — add `cloudCoverMid INTEGER` to Android `observations` (Room 68→69) and the
   desktop observations table (schema 22→23), mirroring the `cloudCoverLow` column plumbing.
5. **Blend** — decide whether `MetarCloudBlender` blends a separate mid series (a parallel
   `Map<Long,Int>` beside `retroCloudActual`) or whether mid actuals are stored-but-unrendered for
   now (matching the forecast phase's "store first, visualize later").
6. **Tests** — `MetarSkyCover` mid-bucket cases (below/inside/above, null base, empty list, unknown
   amount); mapper round-trips; Android 68→69 migration test; desktop v22→23 JDBC round-trip.

## Verification

- `:shared:testShortShared`, `:app:testShortDebugUnitTest`, `:desktop:testShortDesktop` green.
- Migration tests (Android instrumented + desktop schema-upgrade) green.
- Live inspection: query `observations` after an NWS/Synoptic fetch and confirm `cloudCoverMid`
  populates only from layers whose base sits in 3–3.6 km, and is absent above it.

## Out of scope

- High-cloud actuals (unobservable by ceilometer).
- Forecast-side mid/high (already Open-Meteo-only, by design).
- Any new visualization — this is store-first, mirroring 68ef68af.
