# Fix Samsung Current-Temp Charging Loop Stalls

## Summary

Samsung evidence from May 19 showed the last successful current-temp fetch at 08:32, followed by display refreshes at 08:50-08:52 that reused an 08:15 NWS observation. The charging-loop scheduler logged requested `KEEP` work, but those log rows did not prove what WorkManager actually kept as the active unique work.

The first installed state-aware build proved the 09:00 current-temp worker ran successfully, but exposed a second failure: while scheduling its successor, the worker still appeared as the active `RUNNING` unique work, so the scheduler kept it and did not enqueue the next 10-minute run. The fix is to make charging-loop scheduling state-aware and to ignore the currently finishing worker when scheduling its successor.

## Implementation

1. Add scheduler decision logic for `scheduleNextChargingUpdate()`:
   - Keep active `RUNNING` work.
   - Keep active `ENQUEUED` work due within the expected 10-minute interval plus a 2-minute grace window.
   - Replace overdue `ENQUEUED` work with immediate current-temp work using reason `charging_loop_overdue`.
   - Replace far-future `ENQUEUED` work with a corrected delayed charging-loop request.
   - Enqueue normal delayed charging-loop work when no active unique work exists.
   - When called from the current-temp worker after a run, ignore that worker's own `RUNNING` WorkInfo so it can enqueue the successor.
2. Add persisted scheduler diagnostics:
   - `CURR_FETCH_WORK_REQUESTED`
   - `CURR_FETCH_WORK_STATE`
   - `CURR_FETCH_WORK_RECOVERED`
3. Add current-temp worker lifecycle diagnostics:
   - `CURR_FETCH_WORK_START`
   - `CURR_FETCH_WORK_RESULT`
4. Keep immediate/manual current-temp updates using `ExistingWorkPolicy.REPLACE`.
5. Keep full forecast refresh cadence unchanged.

## Tests

1. Add focused tests for the scheduler decision helper:
   - no active work schedules delayed work
   - running work is kept
   - the currently finishing worker is ignored when scheduling its successor
   - due-soon enqueued work is kept
   - overdue work is recovered immediately
   - far-future work is corrected with delayed replacement
2. Keep a test that immediate current-temp update still uses `REPLACE`.
3. Run:
   - `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.CurrentTempUpdateSchedulerTest`
   - `./gradlew test`

## Assumptions

1. Use a 2-minute grace window around the 10-minute charging-loop interval.
2. Recovery applies only to the plugged-in and screen-interactive charging-loop scheduler path.
3. The Current Observations fetch-log UI keeps its existing targeted limit; no UI limit change is part of this fix.
