# Observation fetch: stop swallowing HTTP errors (tri-state outcomes)

**Date:** 2026-07-13
**Status:** IMPLEMENTED & verified live on Samsung (see summaries/260713-observation-fetch-error-hardening.md)
**Trigger:** Accepted caveat from the fetchedAt-attempt change: transport failures collapse into
the same `null` as "station has no valid data", so a mid-cycle network drop could touch a
station we never reached, and real HTTP errors vanish without persisted reporting.

## Where errors are swallowed today

1. **`NwsApi.getRecentValidObservationDetailed` (:shared)** — `catch (e) → null`. Callers cannot
   distinguish NoData from transport failure. Consequence: the app's `NWS_STATION_FAIL` log path
   is currently dead code (the API never throws).
2. **`SynopticApi.fetchSynopticObservations` (:shared)** — returns `null` for transport errors,
   API-level failures (RESPONSE_CODE≠1), and absent station/observation structures alike.
3. **Desktop `bestEffort` wrapper** — swallows any exception → null; `.orEmpty()` then turns a
   network error into "station has no history", which (since today) wrongly touches fetchedAt.
   (`NwsApi.getObservations` itself correctly throws — the wrapper is the swallow point.)

## Change

1. **`:shared` tri-state result** — small sealed type next to NwsApi:
   `FetchOutcome<T>`: `Success(value)` / `NoData` / `Failed(reason)`. `NoData` = the HTTP
   conversation completed and the source definitively has nothing usable; `Failed` = we never
   got a usable answer (transport, HTTP status, parse).
2. **`NwsApi.getLatestObservationDetailedResult(stationId): FetchOutcome<Observation>`** —
   Success(first valid of last 10) / NoData (response OK, no valid features) / Failed(exception).
   Migrate both callers; delete the old nullable method (only 2 call sites).
   Extract the feature-walk into a pure `selectValidObservation(responseJson): FetchOutcome`
   so it's testable with fixture JSON strings, no HTTP (repo testing strategy: pure-function
   extraction, no mocks).
3. **`SynopticApi.fetchSynopticObservations` → `FetchOutcome<List<Observation>>`** — Failed on
   exception/RESPONSE_CODE≠1; NoData on missing structures or empty series; Success otherwise.
4. **Shared touch decision** — one pure function used by BOTH platforms:
   `shouldTouchFetchedAt(primary: FetchOutcome<*>, fallback: FetchOutcome<*>?): Boolean` —
   true iff nothing was stored AND at least one source completed with a definitive NoData;
   false when everything Failed (we learned nothing about the station).
5. **App `ObservationRepository.fetchStationObservation`** — branch on outcomes:
   - any Success → store (unchanged)
   - decision fn true → touch + `OBS_ATTEMPT_TOUCH` (unchanged tag)
   - all Failed → NO touch; log `NWS_STATION_FAIL station=X reason=...` (revives the dead path,
     now with real reasons: exception class + message)
6. **Desktop `fetchObservationBundles`** — replace the `bestEffort{...}.orEmpty()` around the
   historical fetch with explicit outcome handling: empty-list (NoData) → touch;
   exception (Failed) → no touch + persisted `NWS_STATION_FAIL` row in app_logs (desktop
   currently only console-logs these). Volume is bounded: per station per refresh, and offline
   refreshes stop after the retry schedule.

## Tests

- **:shared** — fixture-JSON tests for `selectValidObservation`: valid-first, null-temp-only
  (KNUQ shape — reuse a LICZ-style null-temperature fixture), empty features, malformed JSON →
  Failed. Synoptic mapping: RESPONSE_CODE≠1 → Failed; empty series → NoData.
- **:shared** — decision-table test for `shouldTouchFetchedAt` (NoData/NoData → touch,
  NoData/Failed → touch, Failed/Failed → no touch, Failed/NoData → touch, Success short-circuits).
- Existing `ObservationDaoTouchTest` / desktop DAO test unchanged (touch mechanics already pinned).
- Mutation check: flip the Failed/Failed row of the decision table to confirm the test bites.

## Verification

- Full suites (:app, :shared, :desktop).
- Samsung live: KNUQ (still broken upstream) must still produce `OBS_ATTEMPT_TOUCH` (NoData
  path). Transport-failure path: toggle airplane mode mid-cycle is unreliable to script —
  covered by the decision-table tests instead.

## Out of scope

- Retry-policy changes, UI changes, other `bestEffort` uses on desktop (forecast paths) —
  observation pipeline only.
