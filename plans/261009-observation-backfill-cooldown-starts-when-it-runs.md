# Observation backfill: the cooldown starts when a backfill runs, not when one is requested

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

## Fix
1. **Stamp the cooldown when a backfill finishes.** `handleObservationBackfillWork` records
   completion for the fetched site (`LocationMatch`-quantized key, time) after
   `backfillRecentNwsObservations` returns — success or unreachable (the retry policy already owns
   unreachable).
2. **Gate requests on "completed recently" or "already pending".** `maybeEnqueueHourlyObservationBackfill`
   requests when the site has no completion within `HOURLY_BACKFILL_COOLDOWN_MS` and no backfill is
   ENQUEUED/RUNNING (the existing `KEEP` + overdue-replace logic in
   `enqueueRequiredObservationBackfill` stays the dedup). A gap the provider genuinely cannot fill
   still waits 30 min after the attempt, so paint-time checks cannot hammer NWS.
3. **Make the silent drop visible.** The `isTestingMode()` early return logs that it dropped work
   (tag + reason), so a sighting like this one is one query instead of a WorkManager DB pull.
4. Shared pure decision (`ObservationBackfillGate`: lastCompletedMs, pending, now → request/skip
   with reason) so the rule is unit-testable without WorkManager.

Android only: desktop has no WorkManager deferral (its backfill runs inline in the refresh).

## Tests
- Pure gate: requested-but-never-completed → request again; completed 10 min ago → skip cooldown;
  pending → skip pending; completed 31 min ago → request.
- Robolectric: enqueue, drop the work without running it, re-evaluate → a new request is made
  (fails today: `reason=cooldown`).
- Worker: a run records completion; the next evaluation inside 30 min skips with `cooldown`.
- Emulator: reproduce by force-stopping the app while a backfill is pending; the next paint
  re-requests and `OBS_HOURLY_BACKFILL_RESULT` follows; the 9a–4p hours fill in.
