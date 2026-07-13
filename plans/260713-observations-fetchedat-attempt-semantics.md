# observations.fetchedAt: last successful store → last completed attempt

**Date:** 2026-07-13
**Status:** IMPLEMENTED & verified live on Samsung (see summaries/260713-observations-fetchedat-attempt-semantics.md)
**Trigger:** KNUQ showed "Fetched 2:04 AM" at 7:30 AM in the Samsung Current-observations
activity while the fetch pipeline was healthy — the *station's* feed is broken upstream
(null temperatures; LICZ-contaminated METARs), not the fetch. User direction: don't add UI;
change `fetchedAt` to mean "last fetch attempt" so Reported-vs-Fetched divergence naturally
distinguishes a stale station from a stale fetch.

## Diagnosis recap (why fetchedAt froze at 02:04)

- The 10-min cycles ran all night (CURR_FETCH logs confirm) and `insertAll(REPLACE)` already
  refreshes `fetchedAt` whenever *any* storable observation comes back — even a repeated stale
  one (that's why the 20:15 ob's fetchedAt reads 02:04, not 20:3x).
- `NwsApi.getRecentValidObservationDetailed` walks the station's last 10 reports for one with a
  usable temperature. Once KNUQ's window contained only null-temp/LICZ reports (~02:04 onward),
  it returned null every cycle; `fetchStationObservation` then also strikes out on the Synoptic
  fallback, returns null, and **nothing is stored or touched** → fetchedAt frozen.
- Gap to fix: a *completed* attempt that yields nothing storable leaves no trace in the row the
  UI displays.

## Change

1. **`ObservationDao` (app)** — new update query:
   `UPDATE observations SET fetchedAt = :nowMs WHERE stationId = :stationId AND timestamp =
   (SELECT MAX(timestamp) FROM observations WHERE stationId = :stationId)`
   as `touchLatestFetchedAt(stationId, nowMs)`. Touching only the newest row is sufficient —
   the activity shows `groupBy(stationId).first()`.
2. **`ObservationRepository.fetchStationObservation` (app)** — when `finalObservation == null`
   (NWS latest-valid walk found nothing AND Synoptic fallback yielded nothing), call
   `touchLatestFetchedAt(stationId, now)` and log a breadcrumb
   (`OBS_ATTEMPT_TOUCH station=X reason=no_valid_observation`, INFO — fires once per cycle per
   broken station, low volume).
3. **Desktop parity** (same divergence exists in ObservationsWindow's Reported/Fetched line):
   equivalent SQL in `DesktopWeatherDao` + touch call at the desktop NWS per-station fetch
   miss. Same semantics, same breadcrumb tag.
4. **UI: unchanged** on both platforms. `observeLatestFetchedAt()` will now tick on touches,
   auto-refreshing the activity — desired.

## Semantics decision (recommended)

Touch on **completed-but-empty** attempts only, not transport failures. In practice the
offline case mostly self-excludes: the multi-station path fails at `getGridPoint` before
reaching per-station calls. Residual caveat: `getRecentValidObservationDetailed` swallows
HTTP errors into the same null as "no valid data", so a mid-cycle network drop could touch a
station it never actually reached. Accepted for now (window is seconds wide); optional
refinement later: tri-state result (Data / NoData / Error) from NwsApi.

## Semantic-change audit

`fetchedAt` on observations becomes "when we last checked this station" rather than "when this
row's data arrived". Consumers reviewed:
- Observations activity Reported/Fetched line — the point of the change.
- `observeLatestFetchedAt()` (activity auto-reload trigger) — improved, ticks on checks.
- `OBS_CURRENT_INSERT` / `fetchAgeMin` logs — written at insert time, unaffected.
- Retention/cleanup: observations cleanup keys on `timestamp` (verify during implementation);
  touch affects only the newest row per station regardless.
- Blend/extrema paths key on `timestamp`, never `fetchedAt`.

## Tests

- Room DAO test (`@Category` bucket required): touch updates only the station's newest row;
  other stations untouched; no-op for unknown station.
- Desktop DAO test (real sqlite temp file, existing harness): same three cases.
- Live verification on the Samsung: KNUQ is likely still broken upstream — after installDebug
  and a fetch cycle, its row should show Reported 8:15 PM / Fetched <now>, while healthy
  stations show both recent.

## Out of scope

- Any UI change (badges, "station silent" notes) — explicitly declined by user.
- Fixing KNUQ itself (upstream NWS/METAR routing issue; nothing to do app-side).
