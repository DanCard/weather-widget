# Observation fetch: stop swallowing HTTP errors (tri-state outcomes)

**Date:** 2026-07-13
**Plan:** [plans/260713-observation-fetch-error-hardening.md](../plans/260713-observation-fetch-error-hardening.md)
**Status:** Implemented & verified live on Samsung (SM-F936U1)

## Problem

The observation pipeline collapsed "station definitively has no usable data" and "the request
failed" into the same `null`: `NwsApi.getRecentValidObservationDetailed` caught every exception
(making the app's `NWS_STATION_FAIL` log dead code), `SynopticApi` returned `null` for transport
errors and API rejections and no-data alike, and desktop's `bestEffort{...}.orEmpty()` turned a
network error into "station has no history" — which since the fetchedAt-attempt change would
wrongly touch a station we never reached.

## What changed

- **`FetchOutcome<T>` (:shared, data.remote)** — `Success` / `NoData` (conversation completed,
  source definitively empty) / `Failed(reason)`. Plus the shared decision
  `shouldTouchObservationFetchedAt(primary, fallback)`: touch only when at least one upstream
  completed with NoData; all-Failed → no touch (a dead network must never masquerade as a
  silent station).
- **`NwsApi`** — `getLatestObservationDetailedResult(stationId): FetchOutcome<Observation>`
  replaces the nullable method (both callers migrated, old method deleted). The feature-walk is
  extracted into pure `selectValidObservation(json, responseJson, stationId)` in the companion
  (fixture-testable, no HTTP); `parseObservationProperties` moved to the companion with it.
- **`SynopticApi`** — returns `FetchOutcome<List<Observation>>`; `Failed` on transport errors
  (now also rethrows `CancellationException` — previously swallowed) and RESPONSE_CODE≠1;
  `NoData` on missing structures/empty series; a Success list is never empty. Parse extracted
  into companion `parseSynopticTimeseries` for fixture tests. The two backfill call sites
  (`NWS_DAILY_SYNOPTIC_FALLBACK`, `OBS_HOURLY_SYNOPTIC_FALLBACK`) adapt via `valueOrNull()` —
  behavior-neutral.
- **App `ObservationRepository.fetchStationObservation`** — outcome-driven: NoData → touch +
  `OBS_ATTEMPT_TOUCH`; all-Failed → no touch + revived
  `NWS_STATION_FAIL station=X nws=<reason> synoptic=<reason>` (WARN, persisted).
  `NWS_STATION_SYNOPTIC_FALLBACK` reason enriched: `stale` / `no_valid_data` / `fail`.
- **Desktop `fetchObservationBundles`** — explicit tri-state around the historical fetch
  (replacing `bestEffort{}.orEmpty()`); NoData → touch, Failed → persisted `NWS_STATION_FAIL`
  row in app_logs (previously console-only).

## Tests

- `FetchOutcomeTest` (:shared): fixture-JSON outcome matrix for `selectValidObservation`
  (valid-behind-null-temps KNUQ shape, all-null → NoData, empty features → NoData, malformed →
  Failed), Synoptic mapping (rejection → Failed, no obs → NoData, temps → Success), and the
  touch decision table. Mutation-checked: relaxing the decision to touch on any non-Success
  fails the table.
- Migrated pre-existing tests that mocked/called the old nullable API:
  `DesktopSynopticFallbackTest`, app `NwsApiTest` (4 sites), `WeatherRepositoryNwsParallelTest`.
- Full `:shared`, `:desktop`, `:app` suites green.

## Verification (live, Samsung)

KNUQ still broken upstream. After install + refresh:
`NWS_STATION_SYNOPTIC_FALLBACK station=KNUQ reason=no_valid_data` (new distinct reason) →
`OBS_ATTEMPT_TOUCH station=KNUQ` → row shows Reported 2026-07-12 20:15 / Fetched 2026-07-13
08:14. Desktop rebuilt and restarted with the same shared code.

## Follow-ups / notes

- Transport-failure path (all-Failed → NWS_STATION_FAIL, no touch) is exercised by the decision
  table, not live (would require killing the network mid-cycle).
- Changes are uncommitted (user to decide on commit).
