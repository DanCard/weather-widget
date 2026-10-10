# Desktop stops storing the NWS blend; computes it on read like Android

## Problem
Both platforms build the synthetic `NWS_BLEND` observation with the shared `NwsBlend.build`, but
only desktop **persists** it:

| | Android | Desktop |
|---|---|---|
| Built | on every read (`CurrentObservationReader`) | once per fetch (`DesktopWeatherService`, 2 paths) |
| Stored in `observations` | no | yes, `stationType = 'BLENDED'` (398 rows today, plus 82 legacy `VIRTUAL`) |
| Read back | — | `DesktopWeatherRepository` prefers the newest stored `NWS_BLEND` row |

Consequences:
- Desktop's blend is frozen at fetch time. The IDW decays station weight over 3 h; Android
  re-weights each read, desktop keeps the fetch-time weights until the next fetch (hours on battery).
- A synthetic row in a table of real stations: ~15 readers filter `stationId != "NWS_BLEND"`, and
  the Observations list filters `stationType != "BLENDED"`.
- It is why `BLENDED` shows up only in the desktop DB, which blocks a clean station-type enum
  (`plans/261009-station-type-enum-integer-codes-in-db.md`).

Android stopped storing it around 2026-07-30 (`a69cedcf`); desktop never followed.
`plans/261002-share-nws-blend.md` shared the computation, not the storage decision.

## Plan
1. **Stop writing it.** Both NWS fetch paths in `DesktopWeatherService` keep using the blend for
   the header's `currentTemp`, but drop it from the rows they persist
   (`rawObservations + listOfNotNull(blend)` → `rawObservations`).
2. **Compute on read.** `DesktopWeatherRepository` builds the blend from the NWS station rows it
   already loaded (`NwsBlend.build`, at `now`) when NWS provides the displayed source's actuals,
   and uses it for `currentObservedAt` / current condition exactly where it read the stored row.
   Falls back to the newest source observation, as today when no blend row exists.
3. **Share the read rule — implemented later the same day.** First skipped as unneeded (both called
   `NwsBlend.build`), then found to differ in its *inputs*: Android windowed at the caller's
   `sinceMs` (local midnight, so at 00:15 a 23:30 reading the IDW still weights was dropped) and
   collapsed to one fetch site (`selectNearestObservationSite` — the excursion bug
   `ObservationSiteMerge` exists for); desktop used 6 days, merged. Now both call
   `NwsBlend.current(readings, lat, lon, nowMs)`: NWS rows only, window = `BLEND_MAX_AGE_MS` (3 h)
   before now, sites merged. Android's `CurrentObservationReader` reads the raw NWS candidates
   (the collapsing `getLatestNwsObservationsByStationAllTime` wrapper is deleted); its `_MAIN` pick
   still collapses (site identity is its question). Tests: `NwsBlendTest` (window, NWS-only, merge),
   `CurrentObservationReaderTest` (after midnight; walk-away fragment).
4. **Purge stored rows — by hand, no migration.** Only the user's own desktop DB ever held them
   (Android never stored the row). After the new code was running: backed up the DB and ran
   `DELETE FROM observations WHERE stationId = 'NWS_BLEND'` (2026-10-09: 398 BLENDED + 82
   VIRTUAL). A schema bump was considered and dropped — a version for a one-off delete on one
   machine is overkill, and 10-day retention would have cleared them anyway.
5. **Drop the desktop-only guards.** With no stored rows, `ObservationsWindow`'s
   `stationType != "BLENDED"` filter is dead and goes. Android's in-memory blend still sits in
   lists alongside real stations (`ObservationResolver` picks it), so the shared `NWS_BLEND`
   stationId filters stay.

## Tests
- `DesktopNwsBlendStaleTest` widened: fresh stations also produce no stored `NWS_BLEND` row.
- Repository test: with only station rows stored, `currentObservedAt` / condition come from the
  computed blend (same values the stored row used to give); non-NWS actuals ignore it.
- `DesktopObservationRecentWindowTest` no longer expects `NWS_BLEND` among stored rows.
- Manual: back up the desktop DB, restart the desktop app, check header temp / observed-at /
  actual line match Android for the same location, and no `NWS_BLEND` rows after a refresh.

## Follow-on
The station-type enum plan then drops `BLENDED` from the DB codes: the stored column only ever
holds `UNKNOWN / OFFICIAL / PERSONAL / RAWS`. `BLENDED` survives only as the in-memory blend
row's type, if the enum needs it at all.
