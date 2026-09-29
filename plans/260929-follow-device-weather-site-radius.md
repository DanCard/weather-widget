# Follow-device: move to a new weather site only beyond ~2 km

Status: **plan.** User's suggestion (2026-09-29): "It doesn't make sense to store a bunch of location
history for essentially the same location in regards to weather."

## Problem

`GpsResampler.followDeviceIfMoved` (`GpsResampler.kt:147`) treats any passive fix that is not
`LocationMatch.sameSite` with the active location (**0.002°, ~200 m**) as a move, and
`LocationUpdater.applyFollowDeviceLocation` makes it the new active site. Every new site gets its own
fetches and its own rows in `forecasts`, `hourly_forecasts` and `hourly_forecast_history`.

Evidence, Pixel 7 Pro DB (2026-09-29):
- A week in Kyiv (09-16..24) left **13 hourly sites** inside ±0.1°, most 200 m–1 km apart; the render
  load reads all of them to stitch one (`HOURLY_LOAD ... inSites=13`).
- DB-wide: 94 distinct sites at the 3-dp storage key; 57 if snapped to 0.02°.
- Most location bugs in the memory index are "coordinate fragmentation": the ±0.1° `ROOM_WHERE`
  box, 0.002° `sameSite`, the stitcher's 0.01° borrow, `unifyToNearestSite`. Fewer sites means fewer
  chances for those rules to disagree.

200 m is the right answer to "is this GPS jitter?" and the wrong one to "is this different weather?".
Forecast grids are coarser than that: NWS ~2.5 km, Open-Meteo 1–11 km (ICON-D2 ~2 km, HRRR ~3 km).

## Change

1. **Add `LocationMatch.WEATHER_SITE_RADIUS_DEG = 0.02`** (~2.2 km latitude, ~1.4–1.8 km longitude
   at 40–50°N) and a shared predicate `sameWeatherSite(a, b)`. It sits beside `sameSite`; that one
   keeps its meaning (fragment merging) and its callers.
2. **`GpsResampler.followDeviceIfMoved`:** a fix within `sameWeatherSite` of the active location is
   not a move. Log `GPS_RESAMPLE outcome=same_weather_site dist=…` (a new token, so "why didn't it
   move?" stays answerable from a pulled DB).
3. **"Use precise device location" (ConfigActivity):** a fix within the radius of the *current*
   active site keeps that site's coordinates and switches the mode to `follow_device`. It doesn't
   write a new site 500 m away. Explicit search and coordinate picks keep their exact coordinates:
   the user named that point.
4. **Moves are measured from the active site, not the last fix.** No drift: walking 1.5 km, then
   another 1.5 km, moves once, at the point you are >2 km from where the site was set.

Existing fragments are not migrated; they age out under the 30-day retention.

## Trade-offs to check before shipping

- **Place name:** the label stays the active site's. Crossing into a neighbouring town less than 2 km
  away keeps the old name until the next real move.
- **Observation blend centre:** the IDW actuals are centred on the site, not the phone. A site up to
  2 km off shifts station weights (Mountain View: AW020 2.2 km vs KNUQ 3.7 km — a 2 km offset can swap
  the dominant station). **Measure first:** on a pulled DB, recompute the current-temp blend at the
  true fix vs the site for offsets 0.5/1/2 km at the Mountain View, Warsaw and Kyiv sites. If the
  typical difference is above ~0.5 °F, use 1 km (0.01°, the stitcher's borrow radius) instead.
- **Current temperature:** interpolated from the site's hourly forecast; within one grid cell by
  construction.

## Tests

- `:shared`: `sameWeatherSite` at 0.019° vs 0.021° in latitude and longitude; symmetric.
- Robolectric (GpsResampler + LocationUpdater + ActiveLocationResolver, real prefs):
  - a fix 500 m away does not move and logs `same_weather_site`;
  - a fix 3 km away moves;
  - two 1.5 km steps move once, on the second step (distance from the site, not the last fix);
  - a pinned (`fixed`) location still logs `skipped_pinned` first.
- ConfigActivity precise-location test: a fix 500 m from the active site keeps the site coordinates
  and sets `follow_device`.
- Device: walk or drive about 1 km with follow-device on and confirm no `location_moved`; the next
  real move beyond 2 km applies at once, as today.

## Docs

CLAUDE.md "Location mode" / "A detected move is applied immediately": add the ~2 km weather-site
radius and the new outcome token.

## Outcome (2026-09-29): 1 km, not 2 km

**Blend measurement** (the plan's gate). A temporary harness ran the real
`ActualTemperatureSeriesBuilder.blendObservationSeries` on the pulled Pixel DB, with every station's
distance recomputed from centres shifted in 8 directions. Station coordinates came from Synoptic
(the app's own token); the distances recomputed at the true site matched the stored ones to within
0.05 km.

| Offset | NWS Mountain View: mean | p95 | max | max at "now" |
|---|---|---|---|---|
| 0.5 km | 0.09 °F | 0.23 °F | 0.47 °F | 0.29 °F |
| 1 km | 0.20 °F | 0.58 °F | 1.43 °F | 0.87 °F |
| 2 km | 0.45 °F | 1.92 °F | 5.85 °F | 3.13 °F |

Open-Meteo and Silurian actuals (Warsaw, Kyiv, Mountain View): **0.00 °F** at every offset; their
actual is the provider's own series at distance 0, which wins the blend's near-zero fast path.
The 2 km tail comes from the nearest station (AW020, 2.2 km) swapping in and out, so the radius is
**1 km**, in km (great-circle) rather than degrees: 0.01° of longitude is only 0.71 km at 50°N.

**Implemented:** `LocationMatch.WEATHER_SITE_RADIUS_KM` / `sameWeatherSite` (`:shared`),
`GpsResampler` (`outcome=same_weather_site distKm=`), and `ConfigActivity.saveDeviceFix`
(`DEVICE_FIX snapped_to_active_site`).

**Tests:** `LocationMatchWeatherSiteTest`; `GpsResamplerTest` (500 m no move, 3 km moves, two
0.7 km steps move once); `ConfigActivityAddFlowRoboTest` (precise fix 500 m keeps the site, 3 km
moves). Mutation-checked with the radius set to 0.

**Device (Pixel 7 Pro, `DebugLocationOverride`):** site Wola (52.233414, 20.970348):
- +500 m → `same_weather_site … distKm=0.50`;
- +3 km → `location_moved` (Żoliborz);
- override cleared → the real fix moved it back to Wola.

