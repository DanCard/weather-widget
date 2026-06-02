# Samsung: current temp slow to refresh from API (on battery)

**Date:** 2026-06-01
**Devices:** Samsung SM-F936U1 (Galaxy Z Fold, RFCT71FR9NT), compared against Pixel 7 Pro

## Root cause (diagnosed from live device logs, not just code)

The Samsung's current temp was slow to refresh **on battery** because of an app-induced
**race condition**, not Samsung battery optimization:

- `OpportunisticUpdateJobService` (every 30 min) enqueues **two** workers together — a fast
  UI-repaint (~1.9 s) and a slower network fetch (~4 s), the latter under the shared unique
  work name `WORK_NAME_CURRENT_TEMP`.
- The UI worker finishes first and called `manageCurrentTempLoopAfterRun(isPlugged=false)`,
  whose on-battery branch ran `CurrentTempUpdateScheduler.cancel(context)`.
- `cancel()` cancels **by unique work name** → it killed the still-running fetch
  (`stopReason=1` = CANCELLED_BY_APP, i.e. our own cancel). Only NWS + Open-Meteo made it
  through each cycle; Silurian/Tomorrow.io basically never refreshed.
- **Why Samsung-only:** it's a race. The Pixel's WorkManager let the fetch win
  (`CURR_FETCH_COMPLETE reason=opportunistic_job`); Samsung consistently let the UI worker
  win and cancel it.

### Key log evidence (Samsung, on battery, 17:33 cycle, pre-fix)

```
17:33:31 CURR_FETCH_WORK_START   reason=opportunistic_job isPlugged=false
17:33:32 CURR_FETCH_SOURCE_RESULT source=NWS success=true
17:33:33 CURR_FETCH_SOURCE_RESULT source=OPEN_METEO success=true
17:33:33 SYNC_PERF uiOnly=true total=1872ms          <- UI worker finished
17:33:33 CURR_FETCH_LOOP_STOP reason=policy_blocked plugged=false
17:33:33 CURR_FETCH_WORK_CANCELLED name=weather_widget_current_temp
17:33:33 CURR_FETCH_CANCELLED ... stopReason=1 msg=Job was cancelled
```

Pixel, same cadence: `CURR_FETCH_COMPLETE reason=opportunistic_job successCount=3/4` (fetch
wins the race and completes).

## Fix

The worker should never cancel the current-temp loop. The charging-loop heartbeat is a
self-perpetuating chain, so on battery it ends naturally by not rescheduling; prompt
teardown on unplug is already handled by `ScreenOnReceiver`. The decision was extracted into
a pure function:

`CurrentTempFetchPolicy.postRunLoopAction()` → `PostRunLoopAction.{SCHEDULE_NEXT, NO_RESCHEDULE}`.

The enum deliberately has **no `CANCEL` value**, so the dangerous cancel-by-name cannot be
reintroduced through this path even by accident.

### Verified live on the Samsung, on battery (forced the exact race)

```
18:07:58 CURR_FETCH_START targets=NWS, OPEN_METEO, SILURIAN   (isPlugged=false)
18:08:00 SYNC_PERF uiOnly=true total=1766ms                  <- UI worker finished
18:08:00 CURR_FETCH_LOOP_STOP ... plugged=false action=no_reschedule  <- fixed branch, NO cancel
18:08:01 CURR_FETCH_SOURCE_RESULT source=OPEN_METEO success=true      <- fetch continued
18:08:02 CURR_FETCH_SOURCE_RESULT source=SILURIAN success=true
18:08:02 CURR_FETCH_COMPLETE reason=opportunistic_job successCount=3
```

No `CURR_FETCH_WORK_CANCELLED`, no "Job was cancelled".

## Tests

The codebase has **no WorkManager worker test harness** — it deliberately tests **pure
policy functions** (there was already a `CurrentTempFetchPolicyTest.kt`). So rather than mock
the worker, the decision was made a pure function and pinned. The strongest guard isn't the
assertion — it's the **type**: `PostRunLoopAction` has no `CANCEL` value.

Two new tests in `CurrentTempFetchPolicyTest.kt`:
- charging → `SCHEDULE_NEXT`
- on battery → `NO_RESCHEDULE` (documented as "never cancels concurrent fetch")

All 8 policy tests + adjacent scheduler/screen-on tests pass.

## Files changed

- `app/src/main/java/com/weatherwidget/widget/CurrentTempFetchPolicy.kt` — new
  `postRunLoopAction()` + `PostRunLoopAction` enum
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` — consume the policy;
  removed the cross-worker `cancel()`
- `app/src/test/java/com/weatherwidget/widget/CurrentTempFetchPolicyTest.kt` — regression
  tests

## Verification recipe (on-device)

```bash
# build + install to Samsung
ANDROID_SERIAL=RFCT71FR9NT ./gradlew installDebug

# simulate on-battery (just `unplug` leaves status:2 charging -> isEffectivelyCharging stays true)
adb -s RFCT71FR9NT shell "dumpsys battery unplug; dumpsys battery set status 3; dumpsys battery set level 55"

# force the opportunistic job (JOB_ID 1002)
adb -s RFCT71FR9NT shell cmd jobscheduler run -f com.weatherwidget 1002

# pull DB + query (note: CURR_FETCH has a 5-min freshness skip -> CURR_FETCH_FRESH_SKIP)
adb -s RFCT71FR9NT exec-out run-as com.weatherwidget cat databases/weather_database > /tmp/s.db
sqlite3 /tmp/s.db "select datetime(timestamp/1000,'unixepoch','localtime'), tag, substr(message,1,100) \
  from app_logs where tag like 'CURR_FETCH%' order by timestamp desc limit 40;"

# restore real battery state
adb -s RFCT71FR9NT shell dumpsys battery reset
```

## Follow-up worth noting

`ScreenOnReceiver.handleUserPresent` / `handleScreenOff` also call `cancel()` on battery.
They're user-driven and rare, so they don't cause the every-30-min staleness — but if an
unlock-time truncation ever shows up in logs, that's the next place to harden.

## Gotchas observed

- `BatteryStatePolicy.isEffectivelyCharging` returns true when `status == CHARGING(2)`, so
  `dumpsys battery unplug` alone is not enough to simulate on-battery — also
  `set status 3`.
- Samsung adb-over-USB was flaky and kept flipping to a wireless TLS transport
  (`adb-RFCT71FR9NT-...._adb-tls-connect._tcp`), causing 0-byte DB pulls mid-session.
