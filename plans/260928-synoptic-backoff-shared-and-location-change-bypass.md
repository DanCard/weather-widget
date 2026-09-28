# Synoptic backoff: one shared gate, and a location change fetches through it

## Symptom
Fold, 2026-09-28: Warsaw set at 09:14:39, Open-Meteo view showed no yesterday column.

## Root cause
- Open-Meteo's actuals are redirected to Synoptic (`actuals_provider_OPEN_METEO=SYNOPTIC`).
- 09:13:19, still at Kyiv: one Synoptic timeout → `SYNOPTIC_FETCH_BACKOFF_SET streak=1 backoffMin=30`.
- The backoff is global, not per site: Warsaw's first sync logged `SYNOPTIC_FETCH_BACKOFF_SKIP`,
  so no stations → no OPEN_METEO `daily_history` row for yesterday. The emulator, not in backoff,
  got 8 Warsaw stations (EPWA…) and drew the bar. Open-Meteo's own history (705 hourly rows) was
  present all along; it is not the provider for that column.
- Desktop had no Synoptic backoff at all.

## Fix
1. `SynopticBackoff.shouldSkip(now, until, userLocationChange)` — a user location change's forced
   sync is exempt. A failed exempt attempt escalates the backoff like any failure.
2. `SynopticFetchGate` + `SynopticBackoffStore` in `:shared`: the whole state machine (skip,
   bypass, clear on any server answer, escalate on failure) and its log rows
   (`SYNOPTIC_FETCH_BACKOFF_SKIP` / `_BYPASS` / `_SET`), identical on both platforms.
3. Android: `SynopticObservationRefresher` uses the gate over its existing prefs;
   `FullSyncPipeline` passes `userLocationChange = input.locationChangePlace != null`.
4. Desktop: `DesktopSynopticBackoffStore` (file in the app data dir, shared by daemon and UI
   processes); the borrowed-Synoptic fetch runs through the gate; the UI's picker-save
   `repo.refresh(userLocationChange = true)` bypasses. Services built without a store (tests) get
   an in-memory one.

## Tests
- `SynopticBackoffTest`: skip / bypass / expired.
- `SynopticFetchGateTest`: failure starts backoff and next fetch skips; bypass + success clears;
  failed bypass escalates.
- `DesktopSynopticBackoffStoreTest`: cross-instance persistence; corrupt/missing file = no backoff.

## Verification
emulator-5554: planted `streak=1` with 30 min left, moved to Berlin →
`SYNOPTIC_FETCH_BACKOFF_BYPASS … backoffRemainingMin=29`, 11 stations stored, backoff reset to 0.
