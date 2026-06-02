# Fix: Samsung current temperature slow to refresh from API

## Context

On the Samsung (SM-F936U1, Galaxy Z Fold) the large current temperature is slow to
refresh from the network while **on battery**. Live device logs (pulled from
`weather_database.app_logs`) show the root cause is an **app-induced race condition**,
not Samsung Doze / battery optimization.

### Evidence (Samsung, on battery, 17:33 cycle)

`OpportunisticUpdateJobService` fires every 30 min and enqueues **two** workers at once:
1. A UI-only repaint worker (`uiOnly=true`, ~1.9 s) → main `doWork()` path.
2. A current-temp fetch worker (`opportunistic=true, currentOnly=true`, ~4 s) under unique
   work name `WORK_NAME_CURRENT_TEMP`, fetching NWS, OPEN_METEO, SILURIAN, TOMORROW_IO.

The fetch gets through NWS + OPEN_METEO, then the UI worker finishes and (because
`uiOnlyRefresh=true`) calls `manageCurrentTempLoopAfterRun(isPlugged=false)`. On battery
that hits the `else` branch → `CURR_FETCH_LOOP_STOP reason=policy_blocked` →
`CurrentTempUpdateScheduler.cancel(context)`. `cancel()` cancels **by unique work name**,
which is the still-running fetch worker → `CURR_FETCH_ERROR ... Job was cancelled`,
`stopReason=1` (STOP_REASON_CANCELLED_BY_APP — our own cancel, not the OS).

Result: every opportunistic cycle on battery, the fetch is truncated after ~2 sources;
slower sources (Silurian, Tomorrow.io) basically never refresh → stale current temp.

### Why Samsung-specific

It is a race. **Pixel** logs show `CURR_FETCH_COMPLETE reason=opportunistic_job
successCount=3/4` — the fetch wins the race and completes. Samsung's WorkManager
scheduling consistently lets the fast UI worker finish first and kill the fetch. The fix
makes behavior deterministic on all devices.

## Fix

Single change in `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`,
method `manageCurrentTempLoopAfterRun(...)`:

Remove the `CurrentTempUpdateScheduler.cancel(context)` call from the on-battery `else`
branch. Keep the `CURR_FETCH_LOOP_STOP` log (annotate `action=no_reschedule`).

Rationale: the charging-loop heartbeat is a self-perpetuating chain — each charging
iteration enqueues the next via `scheduleNextChargingUpdate`. On battery it therefore dies
naturally by simply not rescheduling; no explicit cancel is required. Prompt teardown on
unplug is still handled by `ScreenOnReceiver` (screen-off / user-present on battery), which
fire at moments where no opportunistic fetch is mid-flight. Worst case without the
worker-side cancel: one already-enqueued successor fires once more on battery, skips the
fetch (policy_blocked), and does not reschedule — negligible battery cost, and far better
than truncating the fetch every 30 min.

The `if` (charging) branch is unchanged: it still schedules the next heartbeat and still
passes `ignoreRunningWorkId` to avoid self-replacement.

> NOTE: I already applied this exact edit to WeatherWidgetWorker.kt before the harness
> returned to plan mode. On approval it just needs to remain (plus the test + verify below).

## Optional follow-up (not required for the fix)

`ScreenOnReceiver.handleUserPresent` / `handleScreenOff` also call
`CurrentTempUpdateScheduler.cancel()` on battery. These are user-driven and rare, so they
do not cause the reported every-30-min staleness. Leave as-is for now; revisit only if logs
show unlock-time cancels truncating a concurrent fetch.

## Test

Add a focused unit test. The cleanest seam is the existing pure decision function rather
than the private worker method:
- `CurrentTempUpdateSchedulerTest.kt` already covers `decideChargingLoopWork`.
- The fix is a *removal* of a side effect; the strongest guard is asserting that the
  on-battery worker path no longer cancels `WORK_NAME_CURRENT_TEMP`. If wiring a Hilt
  worker test is too heavy, document the behavior via the existing scheduler tests and rely
  on on-device log verification (below), consistent with the project's
  pure-function/no-heavy-mocking testing strategy.

Run unit tests:
```
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.CurrentTempUpdateSchedulerTest"
```

## Verification (on-device, end to end)

1. Build + install to the Samsung:
   `./gradlew installDebug` (device RFCT71FR9NT).
2. Unplug the device (on battery), wait for ≥1 opportunistic cycle (~30 min) or trigger
   one, then pull and query logs:
   ```
   adb -s RFCT71FR9NT exec-out run-as com.weatherwidget cat databases/weather_database > /tmp/samsung_weather.db
   sqlite3 /tmp/samsung_weather.db "select datetime(timestamp/1000,'unixepoch','localtime'), tag, substr(message,1,90) from app_logs where tag like 'CURR_FETCH%' order by timestamp desc limit 40;"
   ```
3. Expect: `CURR_FETCH_COMPLETE reason=opportunistic_job successCount=4 ... targets=NWS,OPEN_METEO,SILURIAN,TOMORROW_IO`
   and **no** `CURR_FETCH_WORK_CANCELLED` / `Job was cancelled` for `reason=opportunistic_job`.
4. Confirm the displayed current temp updates within an opportunistic cycle on battery.

## Memory note (after verified)

Add a memory: "Current temp truncated on battery (Samsung) — UI-only worker's
`manageCurrentTempLoopAfterRun` cancel() killed the concurrent opportunistic fetch (same
unique work name). Fix = don't cancel on battery; let the charging chain die by not
rescheduling. Race; Pixel won it, Samsung lost it."
