# aviationweather.gov METAR transport — international actuals + one multi-station call

**Status:** Planned, not implemented. Detailed plan:
`plans/260823-aviationweather-metar-transport.md`.
**Date:** 2026-08-23

## Problem

Two gaps, one cause.

1. **Non-US users have no station actuals at all.** NWS observation discovery routes through
   `/points` → `observationStationsUrl` (`NwsObservationSource:98`). Outside the US that request
   fails (`NWS_GRIDPOINT_FAIL`) and the station list comes back empty. Every non-NWS source in the
   enum is forecast-model output with `supportsTemperatureActuals = false`, so a user in Paris has
   **no** true observation anywhere in the app.
2. **Observation fetching is N calls per cycle**, one per station, across the 5 nearest stations.

## What is planned

One new transport, `aviationweather.gov/api/data`, which closes both:

| | call | cadence |
|---|---|---|
| discovery | `stationinfo?bbox=…` | once / 24 h, cached |
| data | `metar?ids=A,B,C,D,E&hours=N` | once / fetch cycle |

Verified 2026-08-23: no API key, HTTP 200, and `bbox` works worldwide — probed LFPG/LFPO/LFPB and
EGLL/RJTT successfully alongside the Bay Area stations.

Observations are stored under a **new `METAR` WeatherSource**, not `api="NWS"` — a French station's
report is not National Weather Service data, and separate provenance lets METAR and NWS rows coexist
for comparison, which is how this app already treats every other pair of sources.

METAR **supplements** NWS; it never substitutes for it (`no_cross_source_fallback`).

## Why not the alternatives

- **`metar?bbox=` for data too** (skipping `stationinfo`): returns every station in the box,
  unbounded — dense regions could return 50. `ids=` keeps the existing "N nearest stations" shape
  the blend already expects.
- **Store under `api="NWS"`**: wrong outside the US, and it would silently merge two different
  feeds into one provenance bucket.
- **Replace the per-station NWS pulls**: premature. Only after side-by-side comparison shows parity;
  called out as an optional later phase.

## Verification

Enumerated in the plan's Testing section — pure-function tests for bbox math, station filtering, and
JSON parsing (including three live-observed type hazards: `wdir` alternating `340`/`"VRB"`,
`visib:"10+"` as a string, `dewp` alternating int/float), plus an integration test over a captured
payload, plus live on-device verification against a US and a non-US location.

## Follow-ups / open questions

- Does a `supportsHourly = false` source stay out of the display/primary selection UI? Check how
  `GENERIC_GAP` is excluded before assuming.
- bbox expansion strategy for rural areas with <5 METAR stations nearby.
- DB growth from roughly doubling US observation rows (1-month retention; ~3,600 rows/month added).
