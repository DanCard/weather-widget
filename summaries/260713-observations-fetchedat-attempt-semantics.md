# observations.fetchedAt: last successful store → last completed attempt

**Date:** 2026-07-13
**Plan:** [plans/260713-observations-fetchedat-attempt-semantics.md](../plans/260713-observations-fetchedat-attempt-semantics.md)
**Status:** Implemented & verified live on Samsung (SM-F936U1)

## Problem

KNUQ showed "Fetched 2:04 AM" at 7:30 AM in the Current-observations activity while the fetch
pipeline was healthy. Root cause was upstream: KNUQ's NWS feed stopped carrying temperatures
after 8:15 PM (null-temp reports, LICZ-contaminated METARs). The app attempted the station every
cycle, but a completed-but-empty attempt stored nothing and touched nothing, so `fetchedAt`
froze — making a silent *station* indistinguishable from a stalled *fetch*. User direction:
change `fetchedAt` to record the last completed attempt; leave the UI alone.

## What changed

- **`ObservationDao` (app)** — `touchLatestFetchedAt(stationId, nowMs)`: updates `fetchedAt` on
  only the station's newest row (the row the activity displays).
- **`ObservationRepository.fetchStationObservation` (app)** — when the NWS valid-observation
  walk AND the Synoptic fallback both come up empty, touch the row and log
  `OBS_ATTEMPT_TOUCH station=X reason=no_valid_observation` (INFO, once per cycle per broken
  station).
- **Desktop parity** — `DesktopWeatherDao.touchLatestObservationFetchedAt` + the same touch in
  `DesktopWeatherService.fetchObservationBundles` when a station's history query yields nothing.
- **UI: unchanged.** Reported/Fetched divergence now carries the signal: station broken →
  Reported old, Fetched fresh; fetch broken → both old.
- Note: `INSERT OR REPLACE` already refreshed `fetchedAt` whenever *any* storable observation
  came back (even a repeated stale one) — this change only fills the completed-but-empty gap.

## Audit

`fetchedAt` consumers reviewed; one subtlety beyond the plan:
`getLatestNwsObservationsByStationAllTime` pre-filters on `fetchedAt > sinceMs` in SQL, but its
caller re-filters `timestamp > sinceMs` in memory, so touched-but-old rows still drop out of
blends. Retention and blend/extrema paths key on `timestamp`.

## Tests & verification

- `ObservationDaoTouchTest` (Robolectric Room, `@Category(LongDuration)`): touch hits only the
  target station's newest row; unknown station is a no-op.
- Desktop case added to `DesktopWeatherDaoTest` (real sqlite): same assertions.
- Full `:shared:test`, `:desktop:test`, `:app:testDebugUnitTest` green.
- **Live on the Samsung** (KNUQ still broken upstream — perfect test subject): after
  `installDebug` + `ACTION_REFRESH`, app_logs shows
  `OBS_ATTEMPT_TOUCH station=KNUQ reason=no_valid_observation` and the KNUQ row reads
  Reported 2026-07-12 20:15 / Fetched 2026-07-13 07:53 — the silent-station signature.

## Follow-ups / notes

- Accepted caveat: `getRecentValidObservationDetailed` swallows HTTP errors into the same null
  as "no valid data", so a mid-cycle network drop could touch a station it never reached
  (offline cycles mostly fail earlier, at the gridpoint call). Optional refinement: tri-state
  result (Data / NoData / Error) from NwsApi.
- KNUQ itself is an upstream NWS/METAR routing issue (its feed currently carries LICZ's
  reports); nothing to fix app-side.
- Changes are uncommitted (user to decide on commit).
