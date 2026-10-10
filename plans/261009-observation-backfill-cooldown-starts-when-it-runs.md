# Observation backfill: the cooldown starts when a backfill runs, not when one is requested

Approved and implemented 2026-10-09.

## Symptom (emulator, 2026-10-09, Google with NWS actuals)
Hourly temperature graph: the actual line is flat ~62° from 9a to ~11a, then jumps; no NWS
observations exist between 08:40 and 16:xx. User: "A backfill should run when data is missing."

## Evidence
- The emulator was off ~08:30 → 17:41 (uptime 18 min at 17:59), so the 9 h hole is real missing
  local data. Synoptic has the hours; NWS rows stop at the 08:21 fetch.
- 17:41:12 `OBS_HOURLY_BACKFILL_REQ … reason=latest_gap_min=541 delayMs=18471 outcome=enqueued
  requestId=4f1a7c0b…` — the gap was detected and a backfill requested.
- WorkManager (`no_backup/androidx.work.workdb`): `4f1a7c0b` state SUCCEEDED, 1 attempt.
- No `OBS_HOURLY_BACKFILL_RUN` / `_SKIP cause=no_location` / `_RESULT` — the handler never ran.
- `PROC_EXIT … [FORCE STOP] … due to start instr` 17:41:29 and `… finished inst` 17:41:50: an
  instrumented test run (the Room migration test) owned the process when the work came due at
  17:41:30. `doWork()` returns `Result.success()` under `WeatherDatabase.isTestingMode()` with only
  a logcat line.
- Every later evaluation: `OBS_HOURLY_BACKFILL_SKIP … reason=cooldown latest_gap_min=…` /
  `max_gap_min=487`, because `maybeEnqueueHourlyObservationBackfill` stamps the 30-min cooldown
  (`markMissingActualsRefreshRequested`) at **enqueue**.

## Root cause
The cooldown measures "a backfill was asked for", but its job is "don't refetch what was just
fetched". Any path that drops the work — a test process, cancellation, a killed process, an early
return — leaves a known gap unrepaired for the full 30 minutes while the logs say `cooldown`.
The test run was this instance's trigger; the design is the bug.

## Fix (as implemented)
1. **Stamp the cooldown when a backfill starts its fetch.** `handleObservationBackfillWork` writes
   `obs_backfill_attempted_<site>` (site-keyed: `LocationMatch`-quantized lat/lon) **before**
   `backfillRecentNwsObservations`, so success, unreachable, a throw and a kill all count. Stamping
   after the fetch (first draft) let a crashing backfill read as "dropped" and be re-requested on every
   repaint — caught in review by a side agent.
2. **Gate requests on `HourlyBackfillGate`** (pure, `widget/handlers`): attempt inside 30 min → skip
   `cooldown`; no recent request → request `due`; recent request + ENQUEUED/RUNNING → skip `pending`;
   recent request, nothing queued, no attempt since → request `dropped`. WorkManager is consulted only
   in that last branch (`WidgetWorkScheduler.hasUnfinishedObservationBackfill`; inspection failure reads
   as pending = the old behaviour). WeatherAPI's provider-history path stamps no attempt and keeps the
   request-time cooldown.
3. **Pre-check** (`hourlyBackfillCoolingDown`, CLOUD view / daily probe) stays I/O-free: certain only
   about a recent attempt; a recent request with no attempt falls through to the full evaluation.
4. **Test-mode drop** is a `Log.w` with the work's reason (app_logs would land in the test database);
   the gate re-requests what it dropped.
5. `OBS_HOURLY_BACKFILL_REQ` / `_SKIP` carry the gate reason.

Android only: desktop has no WorkManager deferral (its backfill runs inline in the refresh).

## Tests
- `HourlyBackfillGateTest`: dropped → request; pending → skip; attempt inside window → skip; a
  crashed attempt cools down without a WorkManager lookup; untracked path keeps request cooldown;
  pre-check certainty.
- `ObservationBackfillAttemptIntegrationTest` (real worker + state manager + test WorkManager):
  a request that never ran is asked again; a backfill that throws still stamps and cools down
  (verified to fail with the stamp moved after the fetch).
- `HourlyObservationBackfillCooldownTest`, `DailyBackfillProbeCostTest` updated to the attempt key.
- Emulator: reproduce by force-stopping the app while a backfill is pending; the next paint
  re-requests and `OBS_HOURLY_BACKFILL_RESULT` follows; the 9a–4p hours fill in.
