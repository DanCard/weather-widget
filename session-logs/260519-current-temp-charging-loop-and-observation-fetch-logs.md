# 2026-05-19 — Current Temp Charging Loop, Samsung Evidence, and Current Observations Fetch Logs

## Summary

This session investigated why current temperature observations on a plugged-in Samsung were much older than the expected 10-minute charging/screen-on cadence, added persistent diagnostics, fixed the charging-loop scheduling bug, then fixed the "Current Observations" activity's empty "Fetch logs" section.

The main root cause found from Samsung database evidence was that the delayed charging-loop current-temp work used `ExistingWorkPolicy.REPLACE`. Frequent UI-only refresh and heartbeat paths called `scheduleNextChargingUpdate()`, so each call replaced the unique `weather_widget_current_temp` work and pushed its due time another 10 minutes into the future. The fix changed that delayed loop scheduling to `ExistingWorkPolicy.KEEP`, while preserving `REPLACE` for immediate current-temp refreshes.

The later "Fetch logs is empty" issue had a separate root cause: the activity pulled the latest 1000 global app logs and then filtered them in memory. Render/interpolation diagnostics could crowd out the relevant fetch logs, and the filter did not recognize newer current-temp diagnostic tags. The fix added a targeted DAO query for current-observation/fetch logs and expanded the activity formatter/filter to include the new tags.

## User Prompts

1. `When phone is plugged in and charging and the screen is on, how often does the widget fetch current temperature?`
2. `I often see where the temp update is much more stale than 10 minutes.  How to debug?  Should we add logging to the db about updates?`
3. `do it`
4. `On attached samsung, last fetch according to "Current observations" activity is 7:27 am.  It is now 7:46 am.  Why wasn't there an attempt at 7:37 am?`
5. `There is a scripts/backup_databases.py that might help`
6. `I hard wired connected, please retry.`
7. `do it`
8. `commit all and push`
9. `On the "Current Observations" activity : "Fetch logs" is empty.  Please fix.`
10. `Should we increase the logging limit or remove it?`
11. `write detailed session log to session-logs/ dir .  then commit and push`

## Initial Behavior Question

The expected behavior from code was:

1. While plugged in and screen-interactive, current-temperature fetch scheduling uses the charging-loop path.
2. `CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES` is 10 minutes.
3. That path is current-temperature-only. It is separate from the normal forecast WorkManager cadence and separate from UI-only widget redraws.
4. The loop is opportunistic in the sense that it only runs while policy conditions hold; it should not imply a full forecast refresh every 10 minutes.

This made the user's report credible: if the device was plugged in, charging, and screen on, a current-temp attempt should have been scheduled roughly every 10 minutes unless policy conditions changed or the scheduled work was cancelled/replaced.

## Persistent Logging Added

The first implementation added database logging around the current-temperature path so future debugging could be done from `weather_database` backups instead of relying only on transient logcat.

### Files Changed

1. `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`
2. `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
3. `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt`

### New or Expanded Logs

1. `CURR_FETCH_FRESH_SKIP`
   - Logs current-temp freshness short-circuit decisions with age and freshness threshold.
2. `CURR_FETCH_SOURCE_RESULT`
   - Logs per-source current-temp result with source id, success flag, temperature, observed time, observed age, condition, and exception summary when present.
3. `OBS_CURRENT_INSERT`
   - Logs inserted current observation rows from both current-temp repository paths and NWS observation repository paths.
4. `CURR_FETCH_DONE`
   - Expanded to include both `updated=<successful count>` and `attempted=<target count>`.
5. `CURRENT_TEMP_DISPLAY`
   - Logs what the widget display resolver selected, including source, station, observed/fetched ages, and displayed temperature details.

### Verification

After this logging patch:

1. Ran `./gradlew test`
2. Result: passed

## Samsung Runtime Evidence

The attached Samsung was verified as:

1. Serial: `RFCT71FR9NT`
2. Manufacturer/model: `samsung SM-F936U1`

The user noted the phone was hard wired after the first connection was unreliable. A new database backup was then captured successfully with:

```bash
./scripts/backup_databases.py
```

The useful backup was:

```text
backups/20260519_075338_sm-f936u1_RFCT71FR9NT
```

It contained a full `weather_database` copy and passed integrity checks during the backup process.

## 7:27 vs 7:37 Investigation

The user observed:

1. "Current observations" showed last fetch around 7:27 AM.
2. It was then 7:46 AM.
3. The user asked why there was no attempt around 7:37 AM.

The backup showed the important rows:

1. `07:27:58 CURR_FETCH_WORK_ENQUEUED ... dueAt=2026-05-19T07:37:58 ...`
2. `07:27:58 CURR_FETCH_START reason=opportunistic_job targets=NWS, TOMORROW_IO, OPEN_METEO, SILURIAN`
3. `07:27:58 CURR_FETCH_FAIL reason=opportunistic_job Job was cancelled`
4. `07:27:58 CURR_FETCH_CANCELLED ... stopReason=1 msg=Job was cancelled`

Then subsequent UI/heartbeat paths kept scheduling replacement current-temp work:

1. `07:28:25 ... dueAt=07:38:25`
2. `07:30:51 ... dueAt=07:40:51`
3. `07:35:02 ... dueAt=07:45:02`
4. `07:38:32 ... dueAt=07:48:32`

This directly answered the 7:37 question: there originally was work scheduled for about 7:37:58, but later calls replaced the unique current-temp work before it got there. The due time kept sliding forward.

## Root Cause

`CurrentTempUpdateScheduler.scheduleNextChargingUpdate()` used:

```kotlin
ExistingWorkPolicy.REPLACE
```

for the delayed charging-loop work. That method is called by normal widget refresh/heartbeat paths while the device is plugged in and interactive. Since the unique work name is `WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP`, every delayed scheduling call replaced the previous delayed worker.

That made the intended 10-minute loop non-idempotent:

1. A current-temp worker could be scheduled for 7:37.
2. A UI-only refresh at 7:28 could replace it and move it to 7:38.
3. Another refresh at 7:30 could replace it and move it to 7:40.
4. Repeated refreshes could postpone the attempt indefinitely.

The "last fetch 7:27" shown in the activity appeared to be based on observation rows/backfill activity, not proof that a successful current-temp-only fetch completed at that time.

## Scheduler Fix

### Files Changed

1. `app/src/main/java/com/weatherwidget/widget/CurrentTempUpdateScheduler.kt`
2. `app/src/test/java/com/weatherwidget/widget/CurrentTempUpdateSchedulerTest.kt`

### Behavior Change

The delayed charging-loop scheduling path now uses:

```kotlin
ExistingWorkPolicy.KEEP
```

This makes `scheduleNextChargingUpdate()` idempotent while a delayed current-temp worker is already pending. UI refreshes and heartbeats can call it freely without pushing the due time out.

Immediate/manual current-temp updates still use:

```kotlin
ExistingWorkPolicy.REPLACE
```

That preserves the intended behavior for explicit immediate refresh paths.

### Logging Change

The scheduler enqueue log was clarified:

1. It now includes `policy=keep`.
2. It now labels the computed delayed time as `requestedDueAt=...`.

That distinction matters because with `KEEP`, a newly requested work item may be ignored by WorkManager if an existing unique work item is already pending.

### Test Coverage

Added `CurrentTempUpdateSchedulerTest` with two Robolectric tests:

1. `charging loop keeps existing pending current temp work`
2. `immediate current temp update still replaces pending work`

### Verification

1. Ran `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.CurrentTempUpdateSchedulerTest`
2. Result: passed
3. Ran `./gradlew test`
4. Result: passed

### Commit

This scheduler/logging work was committed and pushed as:

```text
1a23c4c Keep pending current temp charging work
```

## Current Observations "Fetch logs" Empty

After the scheduler fix, the user reported that the "Current Observations" activity's "Fetch logs" section was empty.

### Evidence Collected

The activity code showed:

```kotlin
appLogDao.getRecentLogs(1000)
    .filter { WeatherObservationsSupport.matchesFetchLog(it, currentSource) }
```

The support filter only recognized a small older set of tags:

1. `CURR_FETCH_START`
2. `CURR_FETCH_DONE`
3. `CURR_FETCH_SKIP`
4. `CURR_FETCH_ERROR`
5. `CURR_FETCH_EXCEPTION`
6. `CURR_FETCH_FAIL`

A query against the Samsung backup showed the latest 1000 global logs covered only a short recent interval:

```text
count=1000
min=2026-05-19 07:45:34
max=2026-05-19 07:52:57
```

The most common tags in that window were render/interpolation logs:

1. `CurrentTempResolver`
2. `TemperatureInterpolator`
3. `DAILY_EXTREME_BLEND`
4. `DAILY_EXTREME_STABLE`
5. `WIDGET_PAINT`
6. `TEMP_ACTUALS_DEBUG`
7. `CURRENT_TEMP_DISPLAY`

Only a few current-temp scheduling logs were present in that latest-1000 window, and they were mostly `CURR_FETCH_WORK_ENQUEUED`, which the activity ignored.

### Root Cause

The empty UI was caused by two issues:

1. **Wrong query shape:** fetching the latest 1000 global app logs let noisy render/display diagnostics crowd out fetch logs.
2. **Outdated filter:** the screen did not recognize the newer current-temp diagnostic tags added for this investigation.

The fix was not to remove the limit. The better fix was to query a targeted subset of log tags.

## Fetch Logs UI Fix

### Files Changed

1. `app/src/main/java/com/weatherwidget/data/local/AppLogEntity.kt`
2. `app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt`
3. `app/src/test/java/com/weatherwidget/ui/WeatherObservationsActivityRobolectricTest.kt`
4. `app/src/test/java/com/weatherwidget/ui/WeatherObservationsSupportTest.kt`

### DAO Change

Added:

```kotlin
getCurrentObservationFetchLogs(limit: Int)
```

The query targets only relevant diagnostic families:

1. `CURR_FETCH%`
2. `OBS_CURRENT%`
3. `OBS_HOURLY_BACKFILL%`

This keeps a limit for UI safety, but prevents unrelated render/interpolation logs from crowding out the fetch history.

### Activity Change

`WeatherObservationsActivity.loadFetchLogs()` now uses:

```kotlin
appLogDao.getCurrentObservationFetchLogs(200)
```

instead of:

```kotlin
appLogDao.getRecentLogs(1000)
```

The empty-state text now says:

```text
No current observation fetch logs found for <source>.
```

### Filter and Formatter Updates

The fetch-log support now includes and formats these newer tags:

1. `CURR_FETCH_SOURCE_RESULT`
2. `OBS_CURRENT_INSERT`
3. `OBS_HOURLY_BACKFILL_START`
4. `OBS_HOURLY_BACKFILL_SKIP`
5. `OBS_HOURLY_BACKFILL_REQ`
6. `OBS_HOURLY_BACKFILL_FAIL`
7. `OBS_HOURLY_BACKFILL_STATION`
8. `OBS_HOURLY_BACKFILL_STATION_FAIL`
9. `OBS_HOURLY_BACKFILL_DONE`
10. `CURR_FETCH_CANCELLED`
11. `CURR_FETCH_FRESH_SKIP`
12. `CURR_FETCH_WORK_ENQUEUED`
13. `CURR_FETCH_WORK_CANCELLED`
14. `CURR_FETCH_LOOP_STOP`

Source-specific tags are filtered by `source=<source id>` where appropriate. Global scheduler/failure tags remain visible for all sources because they explain why a fetch did or did not run.

### Regression Test

`WeatherObservationsActivityRobolectricTest` now inserts 1,100 noisy `CURRENT_TEMP_DISPLAY` rows after the fetch logs, then verifies the activity still shows targeted current-observation fetch logs. This reproduces the Samsung failure mode where global latest-1000 logs were too noisy.

`WeatherObservationsSupportTest` now verifies matching for the newer tags.

### Verification

Focused tests:

```bash
./gradlew testDebugUnitTest --tests com.weatherwidget.ui.WeatherObservationsSupportTest --tests com.weatherwidget.ui.WeatherObservationsActivityRobolectricTest
```

Result: passed.

Full JVM suite:

```bash
./gradlew test
```

Result: passed.

## Logging Limit Decision

The user asked whether the logging limit should be increased or removed.

Recommendation from this session:

1. Keep a limit for the activity UI.
2. Do not remove the limit, because `app_logs` can grow large and opening the activity should not load unbounded diagnostics.
3. Keep the targeted query because it is the real fix.
4. `200` targeted current-observation/fetch logs is more useful than `1000` mixed global logs.
5. If longer scrollback is desired later, increase the targeted UI limit modestly, for example to `500`.
6. If a complete diagnostic export is needed, add an explicit export/debug path rather than making the default activity query unbounded.

## Final Working Tree Before This Log

Before writing this session log, the uncommitted fetch-log fix touched:

1. `app/src/main/java/com/weatherwidget/data/local/AppLogEntity.kt`
2. `app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt`
3. `app/src/test/java/com/weatherwidget/ui/WeatherObservationsActivityRobolectricTest.kt`
4. `app/src/test/java/com/weatherwidget/ui/WeatherObservationsSupportTest.kt`

This session log was then added as:

```text
session-logs/260519-current-temp-charging-loop-and-observation-fetch-logs.md
```

## Follow-Up Notes

1. Once the new build is installed on the Samsung, the Current Observations screen should show scheduler/source/insert logs even if display/render logging is busy.
2. Future stale-current-temp investigations should start with `scripts/backup_databases.py` and then inspect `app_logs` for `CURR_FETCH%`, `OBS_CURRENT%`, and `CURRENT_TEMP_DISPLAY`.
3. If the screen still looks empty after this fix is installed, the next evidence to collect is a fresh Samsung backup and a direct query of `app_logs` for the targeted tag families.
