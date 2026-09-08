# 260908 — Synoptic stale pool painted a flat actual line on the Fold

**Date:** 2026-09-08 · **Plan:** — (diagnosis session; no plan file) ·
**Status:** implemented, tested (4039 unit tests pass), and verified on the Fold emulator;
**not committed**

## User prompts

> synoptic data messed up on Fold emulator. Just a straight line for actual temperature data on
> temperature hourly graph.

> both

## Evidence and root cause

The Fold emulator's (emulator-5556) hourly graph drew the pink actual line dead flat at 71.6°,
labeled "knuq 71.6° @ 11:55 am". The label value turned out to be two days old.

1. **Synoptic API outage.** `app_logs` shows 17 consecutive `SYNOPTIC_FETCH_FAIL
   error=synoptic: null` rows from 2026-09-07 22:44 to 2026-09-08 16:01. That error is built in
   `SynopticApi.parseRadiusTimeseries`: Synoptic answered `RESPONSE_CODE != 1` with a null
   `RESPONSE_MESSAGE` — an API-level rejection (most likely free-token quota), not a network error.
   Sept 6 logs show the 24h radius fetch (~34k raw rows, ~2000 stored) running every ~5–10 minutes,
   which is very likely what exhausted the token.
2. **Stale observation pool.** As of the bad render (16:06), the `observations` table had no
   SYNOPTIC rows newer than 2026-09-06 12:00. (Pulled the DB *with* its `-wal` — the first
   wal-less pull looked two days stale everywhere and misled the first read of the evidence.)
3. **Unbounded carry-forward painted the flat line.** With zero blend points near the visible
   window, `ActualTemperatureSeriesBuilder` seeded `lastActual` from the newest pre-window row —
   the 09-06 11:55 reading, 71.58° — and the carried pass stamped it onto every past hour
   (`isActual=true`). `ACTUAL_EXTREMA highIdx=0 lowIdx=0 temp=71.58` across indices 0..12
   confirmed the rendered series was constant.
4. **Self-recovery at 16:12/16:14** (Synoptic accepted two fetches again; a navigation tap
   triggered the render + 24h backfill), which is why panning the widget forward "fixed" it
   mid-diagnosis.

The phone emulator was unaffected only because its widget displayed METAR actuals, a different
feed. The widget was also left panned 3 h into the past (WIDE zoom pans in 3 h jumps), which is
the state the user saw; the underlying cause was the stale pool, not the panning.

## Change

**Render side — staleness gate (user-visible fix, `:shared`, both platforms)**

1. `ActualTemperatureSeriesBuilder` carry-forward now tracks the timestamp of the last actual
   (including the pre-window seed) and refuses to fill a gap older than `maxCarryGapMs` = 3 h,
   matching `ObservationOrigin.BLEND_MAX_AGE_MS`. A stale pool now ends the actual line at the
   last real observation instead of stretching one stale reading across the window. A VERBOSE log
   records dropped gaps.

**Fetch side — hardening**

2. `SynopticApi.parseRadiusTimeseries`: quota-style rejections no longer log as
   `synoptic: null`; the failure reason now reads `synoptic: RESPONSE_CODE=2 (no message)` so the
   failure class is queryable.
3. New `shared/.../util/SynopticBackoff`: exponential backoff, 30 min doubling to a 6 h cap.
4. `SynopticObservationRefresher` (:app): switched to `fetchObservationsResult` to distinguish
   Success/NoData/Failed, persists fail-streak + backoff-until in SharedPreferences
   (`synoptic_fetch_backoff`, survives process restarts), clears on success/NoData, and logs
   `SYNOPTIC_FETCH_BACKOFF_SET` / `SYNOPTIC_FETCH_BACKOFF_SKIP` DEBUG rows to `app_logs`.
   Desktop was deliberately not wired: its production path uses the Aviation Weather batch, not
   the Synoptic radius fetch.

## Tests

- New `SynopticBackoffTest` (5 tests, incl. "a 17 h outage stays ≤ 8 attempts").
- `ActualTemperatureSeriesBuilderTest`: two regression tests — a two-day-stale pool paints no flat
  line; carry stops exactly at the 3 h horizon (13:00 carried, 14:00+ dropped).
- `SynopticApiRadiusTest`: rejection without a message names the response code; rejection with a
  message keeps the message.
- Updated one legacy test, `TemperatureViewHandlerActualsTest` "actuals outside the zoom window":
  it codified the old unbounded carry (a 06:00 reading coloring a window 4+ h away). It now
  asserts the bounded behavior (obs at 08:10; hours ≤ 11:00 carried, ≥ 13:00 forecast-only). Its
  window comment also said NARROW was -2/+2; it is actually -3/+2.

## Verification

- `./scripts/unit-tests.sh`: 4039 tests pass across `:shared`/`:app`/`:desktop`.
- `:app:assembleDebug` installed (`adb install -r`) on emulator-5556; widget renders a varying
  actual line in the normal view (knuq 87.8° @ 4:15 pm) and in the exact panned-back state that
  was flat before (57.3°→90.6°, 5a–9p window).
- Caveat: the stale-pool flat-line condition itself can no longer be reproduced on-device while
  Synoptic is healthy; it is covered by the unit tests, which replicate the September 6 pool.

## Follow-ups

- Commit (reference this file).
- Consider throttling the 24 h deep Synoptic fetch cadence further (`SynopticFetchPolicy` /
  `SynopticObservationRefresher.DEEP_HOURS`) — the Sept 6 every-~5-min pattern is what burned the
  quota.
