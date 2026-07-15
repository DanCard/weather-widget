# Observations list showed 6 stations when only 5 are fetched (2026-07-15)

## Symptom

Samsung (`RFCT71FR9NT`) "Current observations" listed **6** NWS stations. Only 5 are ever polled
(`ObservationRepository.MAX_RETRIES = 5`).

## Root cause

Not an off-by-one — **the list has no cap at all**. `WeatherObservationsActivity.loadObservations()`
renders every distinct `stationId` in the 24h window that matches the current source. `MAX_RETRIES`
is a *fetch-side* cap; nothing ties the display to it, so the two are free to disagree.

The extra row was a station left behind by a device move:

- Device sat at `37.3414/-122.0422` until 16:05 on 07-14, then moved to `37.4168/-122.089`.
- LSGC1 (LOS GATOS, 17.25 km) was polled **only** from the old site and never refreshed after.
- That old site is 0.075° lat / 0.047° lon from the current one — a genuinely different town, but
  well **inside** the ±0.1° `LocationMatch.ROOM_WHERE` box, so `getRecentObservationsNear` returned
  it and the adapter drew it.

The ±0.1° box is deliberately coarse (~7 mi) to forgive geocoding jitter (`37.4167` vs `37.416883`),
so it structurally cannot distinguish a real neighbouring site. It is a pre-filter, never the final
answer.

## Fix

Added `LocationMatch.selectNearestSite(rows, lat, lon, latOf, lonOf)` to `:shared` (next to
`sameSite`): collapses a raw box result to the single nearest physical site, keeping sub-precision
fragments of that site (`sameSite`, 0.002°) and dropping genuinely different markers.

- `WeatherObservationsActivity.loadObservations()` now pipes `getRecentObservationsNear` through it.
- `GraphDataLoader.unifyToNearestSite` — which already hand-rolled exactly this for
  `HourlyForecastEntity` — now delegates to it, so there is one copy of the logic.

`latOf`/`lonOf` must read the row's stored **device** location (`locationLat`/`locationLon` = where
the fetch happened), not the station's own coordinates; the latter ranks by station distance and
defeats the filter.

Replaying the real device rows through the filter yields exactly AW020, KNUQ, KPAO, LOAC1, KSJC.
LSGC1 is the only station with no rows at the current site.

## Gotcha: `GROUP BY stationId` with bare columns lies

`SELECT stationId, locationLat ... GROUP BY stationId` let SQLite pick an **arbitrary** row's bare
columns. Stations with rows at *both* sites reported whichever location they felt like, producing a
station→site mapping that looked nothing like reality and sent the first diagnosis down the wrong
path twice. Group by `(stationId, lat, lon)` and count rows per pair.

## Gotcha: the bug self-heals in 24h

Stale rows age out of the window (LSGC1 was due to vanish at 15:32 on 07-15) — the symptom
disappears on its own whether or not it is fixed. Pull the DB immediately. A station count exceeding
`MAX_RETRIES` is the tell-tale.

## Desktop

**Not affected** — verified stationary: `observations` holds exactly one site (`37.4168/-122.089`),
and its list correctly shows 5 (NWS_BLEND is filtered as synthetic). Note its query is *entirely*
unscoped (`getRecentObservations`, no location predicate at all), so it would show stale stations for
24h if its location ever changed. Left as-is deliberately.

## Verification

- New `LocationMatchSelectNearestSiteTest` (`:shared`) using the real device coordinates. Proved it
  can fail: mutating the filter to `return rows` failed 2 of its cases; restored and green.
- New Robolectric case `nws mode excludes a stale nearby site that the proximity box admits`. The
  existing sibling test only covered an Austin row, which the SQL box already catches on its own —
  it never exercised the nearby-site case.
- `:shared:test`, `:desktop:test`, and the affected `:app` unit tests pass.
- `installDebug` on the Samsung; widget confirmed rendering live data afterwards.
