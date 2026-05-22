# Fix Charging Loop Stall on Pixel 7 Pro

## Summary
Investigated a report of slow temperature observation updates on a Pixel 7 Pro. Analysis of device logs revealed that the 10-minute "charging loop" update chain was breaking because the `CurrentTempUpdateScheduler` used the `ExistingWorkPolicy.KEEP` policy when scheduling successor work from within a running worker. 

WorkManager's `KEEP` policy does not allow a worker to enqueue its own successor under the same unique name if it is still considered "active" (which it is until it fully returns). This caused the loop to terminate, leaving the widget dependent on a 30-minute opportunistic fallback job.

The fix switches the successor enqueuing policy to `APPEND_OR_REPLACE`, allowing the next update to be correctly queued even while the current one is finishing.

## Evidence (Log Analysis)

Logs from 2:16 PM showed the loop attempt to chain itself but fail:
```text
2026-05-22 14:16:22|CURR_FETCH_WORK_START|id=fc3a5bee... reason=charging_loop
...
2026-05-22 14:16:29|CURR_FETCH_WORK_STATE|type=charging_loop decision=enqueue_delayed active=none ...
2026-05-22 14:16:29|CURR_FETCH_WORK_REQUESTED|type=charging_loop reason=charging_loop decision=enqueue_delayed policy=keep ... workId=78420919...
```
Critically, the `workId=78420919` requested at 14:16:29 never appeared in any subsequent `CURR_FETCH_WORK_START` logs. The next successful start was at 14:43:12 via an `opportunistic_job`, confirming the 10-minute chain had died.

## Implementation
Updated `CurrentTempUpdateScheduler.kt` to use `ExistingWorkPolicy.APPEND_OR_REPLACE` for the `ENQUEUE_DELAYED` action in the charging loop.

```kotlin
// app/src/main/java/com/weatherwidget/widget/CurrentTempUpdateScheduler.kt

val policy = when (decision.action) {
    ChargingLoopAction.ENQUEUE_DELAYED -> ExistingWorkPolicy.APPEND_OR_REPLACE
    // ...
}
```

## Verification
1. **Empirical Reproduction**: Added a unit test `scheduleNextChargingUpdate uses APPEND_OR_REPLACE when enqueuing successor from running worker` in `CurrentTempUpdateSchedulerTest.kt`.
2. **Failure Confirmation**: Verified that the test failed with `AssertionError: argument: KEEP, matcher: eq(APPEND_OR_REPLACE)` when the code still used `KEEP`.
3. **Fix Confirmation**: Verified the test passed after switching to `APPEND_OR_REPLACE`.
4. **Regression Testing**: Ran the full unit test suite (310+ tests); all passed.

```bash
./gradlew testDebugUnitTest --tests com.weatherwidget.widget.CurrentTempUpdateSchedulerTest
```
Result: `BUILD SUCCESSFUL` (8 tests passed).
