# Current Observations leaked stations from a previously-visited location

**Date:** 2026-07-08

## Symptom
On the emulator, the "Current Observations" activity (location = Bay Area / Google HQ) showed
**Austin's KATT "Austin City Austin Camp Mabry"** at 91.0° wedged mid-list, tagged `OFFICIAL (Web)`
with a bogus **"3.6 mi"** distance — while every neighbour was a Bay Area station tagged `(API)`.

## Root cause
Each `observations` row stores the **device location it was fetched under** (`locationLat`/
`locationLon`) plus a `distanceKm` frozen at fetch time. `WeatherObservationsActivity.loadObservations()`
queried `ObservationRepository.getRecentObservations(sinceMs)` — filtered by **source + a 24h window
only, with no location filter**. The emulator had recently been in both Austin and the Bay Area, so
the DB held fresh (<24h) rows from both:

| stationId | stored location | distance | reported |
|-----------|-----------------|----------|----------|
| AW020, KNUQ, KPAO… | 37.42, −122.09 (Bay Area) | 1.4–3.8 mi | today 08:xx |
| **KATT, KAUS, KHYI…** | **30.27, −97.74 (Austin)** | 3.6–29 mi | yesterday 09:xx |

When the 24h window straddles a move between cities, the old city's rows leak into the list, still
carrying their old (misleadingly small) distance. Same bug class as the coordinate-fragmentation
fixes — a location-keyed table read that forgot to scope by location.

## Fix (shipped, verified)
Route through the seam the codebase already trusts: `LocationMatch.ROOM_WHERE` (the ~0.1°/7-mile
"same location" box, already used by `getObservationsInRange`). Query-only — **no schema change, no
migration**.

| Layer | File / function | Change |
|-------|-----------------|--------|
| DAO | `ObservationDao.getRecentObservationsNear(sinceMs, lat, lon)` | New location-scoped variant of `getRecentObservations`, embeds `LocationMatch.ROOM_WHERE`. Backed by the existing `(timestamp, locationLat, locationLon)` index. |
| Repository | `ObservationRepository.getRecentObservationsNear` | Thin wrapper. |
| Activity | `WeatherObservationsActivity.loadObservations` | Resolve `activeLocation ?: weatherRepository.getLatestLocation()`; use the scoped query in **both** the NWS and non-NWS branches. Location-blind `getRecentObservations` survives only as the fallback when no location resolves. |

## Verification
- **Regression test** (`WeatherObservationsActivityRobolectricTest`): new
  `nws mode excludes observations fetched at a different location` inserts a fresh KATT row at Austin
  coords and asserts it's dropped from the Bay Area list. Passes; the rest of the suite stays green
  (the helper stores rows at the widget's pinned location, so existing expectations are unchanged).
- **Live emulator**: rebuilt, installed, reopened the screen via the widget's thermometer icon. List
  is now `AW020 (1.4mi) · KNUQ (2.4mi) · KPAO (3.8mi) · LOS ALTOS (5.2mi) · San Jose…` — the Austin
  KATT row is gone.

## Follow-on rule
Any "recent observations near the user" read must go through a LocationMatch-scoped query; never call
the now-fallback-only `getRecentObservations`.
