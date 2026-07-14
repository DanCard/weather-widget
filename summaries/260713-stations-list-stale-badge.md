# Stations list: "Stale" badge for stations not contributing to the blend

**Date:** 2026-07-13

## Problem

A station that had stopped reporting hours ago still badged its origin — `OFFICIAL (API)` — and
still showed a temperature, as if it were feeding the displayed temp. It was not: both blend
estimators had long since dropped it. The row implied a contribution that did not exist, and its
stale value invited comparison against a current temp it never fed.

## The rule

Staleness is not a display choice. Both estimators decay a station's weight linearly to **zero at
3 hours**:

- `SpatialInterpolator.interpolateIDW` — the `NWS_BLEND` / current-temp path
- `ActualTemperatureSeriesBuilder` — the graph's actual line (`TIME_DECAY_MAX_AGE_MS`)

So a station whose newest reading is ≥3h old contributes literally nothing. That is now the badge's
definition of stale, and both estimators read the constant from one place so the badge cannot drift
away from the blend it describes.

Distinct from `ObservationFallbackPolicy.STALE_AFTER_MS` (1h), which decides when to *re-fetch* a
lagging station from the web source. A station can be stale enough to re-fetch but still fresh
enough to blend.

## Changes

1. **New `:shared` `ObservationOrigin`** (`shared/observations/ObservationOrigin.kt`) — owns
   `BLEND_MAX_AGE_MS` (3h) and `Kind { QC_FAILED, STALE, WEB, API }`. QC failure outranks staleness
   (more specific; never a blend input at any age).
2. **`SpatialInterpolator` + `ActualTemperatureSeriesBuilder`** now read that constant instead of
   each hardcoding `3 * 60 * 60 * 1000L`.
3. **Android `WeatherObservationsActivity`** and **desktop `ObservationsWindow`** both render from
   `Kind` — they previously duplicated the same origin ternary.
4. Stale rows render **red** (`#FF3366`, the existing error accent, shared with QC-failed) and
   **blank the temperature** (`—`), same treatment as a QC rejection: neither value is in the blend.
5. `station_origin_stale` added to all 20 locales.

Each list row is the station's *newest* reading (`groupBy { stationId }.map { first() }` over a
`timestamp DESC` 24h query) on both platforms, so "Stale" means the station itself has gone quiet —
not merely that an old reading is on screen.

## Verification

- `:shared` suite: 469 tests, 0 failures (constants rewired without behavior change).
- New `ObservationOriginTest` pins the badge to the blend: at `3h − 1ms` the origin is `API` and
  `interpolateIDW` returns a value; at exactly `3h` the origin is `STALE` and `interpolateIDW`
  returns null. If the decay window ever moves, this fails rather than letting the list quietly
  claim a station is contributing.
- New Robolectric test `station past the blend decay window renders stale badge and no temperature`.
  Proven to fail when the fixture station is made fresh (`expected <OFFICIAL (Stale)> but was
  <OFFICIAL (API)>`), so it genuinely exercises the branch.
