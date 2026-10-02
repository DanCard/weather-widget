# Share the NWS_BLEND row between Android and desktop

## Problem

The synthetic `NWS_BLEND` observation (IDW of each NWS station's newest usable reading) was built in
three places that disagreed when the IDW had nothing to weight (every station past its 3h decay):

| Where | Result |
|---|---|
| Android `CurrentObservationReader` (read time) | no row |
| Desktop `fetchNwsObservations` (fetch time) | row carrying the **hourly forecast** |
| Desktop `fetchNwsObservationsOnly` (fetch time) | row carrying the **nearest station's stale reading** |

The desktop rows are stored and read by the graph and daily blend as observations, so a forecast (or
a 5h-old single reading) became "the observed blend". They also differed in `stationType`
(`VIRTUAL` vs `BLENDED`) and in which readings counted (desktop did not drop QC-failed rows itself).

Evidence: the desktop DB's 93 retained blend rows include none built from all-stale stations, so it is
latent (needs a 3h+ NWS outage), but a mocked desktop fetch with one 5h-old station reproduces it:
`NWS_BLEND=68.0` from the stale KNUQ reading.

## Fix

`:shared` `NwsBlend` — `latestUsableByStation` (QC-passing newest per station, never an existing blend
row) and `build(...)`, which returns the row or null. Android maps it to `ObservationEntity` at the
quantized storage key; desktop appends `listOfNotNull(blend)`. The desktop header value
(`providerCurrentTemp`) keeps its forecast / nearest-station fallback — that is a display value, not
a stored observation. `stationType` is `BLENDED` on both; nothing reads `VIRTUAL`.

## Tests

- `:shared` `NwsBlendTest`: stale-only → null; no usable station → null; nearest condition, newest
  timestamp, IDW between stations; row filed at the storage key; QC filtering; a prior blend row is
  never an input.
- Desktop `DesktopNwsBlendStaleTest`: mocked NWS with one 5h-stale station → no `NWS_BLEND` row
  (fails on the old code).
- Android `CurrentObservationReaderTest` QC-filter tests now exercise the shared function.
