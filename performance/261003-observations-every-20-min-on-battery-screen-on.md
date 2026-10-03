# Observations every 20 min on battery while the screen is on (≥70%)

## Trigger

Bug report 2026-10-03 14:00 (Pixel 7 Pro): "KNUQ weather station was slow to update, then it updated
quickly in rapid succession."

Device DB timeline:

| Time | Event |
|---|---|
| 13:13 | last charging-loop fetch (`knuq 84.2° @ 12:55 pm`) |
| ~13:20 | unplugged → loop stops (`CURR_FETCH_LOOP_STOP reason=policy_blocked`) |
| 13:33–13:43 | full syncs fetch no new obs; backfill gate `coverage_ok latest_gap_min=41` (threshold >45) |
| 13:47:24 | gap backfill stores KNUQ 1:15; repaint skipped `reason=screen_off` |
| 13:58:33 | 45-min `OPPORTUNISTIC_JOB` fires → paints 1:15, then 2 s later its fetch paints 1:35 |

## Root cause

On battery the only scheduled current-observation fetch is `OpportunisticUpdateJobService`, every
**45 min** (`CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES`, gate `> 65%`). Plus NWS's
5–20 min reporting lag, the widget can show a ~60-minute-old station reading while the user is
looking at it. The charging loop (10/16 min) does not slow down off-charger — it stops.

## User's decision

Fetch recent observations **every 20 minutes when on battery, battery ≥ 70%, and screen on.**
Everything else unchanged (charging 10/16 min; the 45-min job stays as the screen-off / 66–69%
backstop).

## Design

Extend the existing self-perpetuating current-temp loop (WorkManager chain, unique
`WORK_NAME_CURRENT_TEMP`), which already re-evaluates screen state each run. A JobScheduler periodic
job can't be conditioned on screen state, so it is not the vehicle.

### Android `:app`

1. **`BatteryTier` (`:shared`)**: `SCREEN_ON_OBSERVATION_MIN_BATTERY_PERCENT = 70`, inclusive (`>=`).
   Separate from `OPPORTUNISTIC_MIN_BATTERY_PERCENT = 65` (`>`), which keeps gating the 45-min job.
2. **`CurrentTempFetchPolicy`**: one function is the single source of truth:
   `loopIntervalMinutes(isCharging, isScreenInteractive, batteryLevel): Long?`
   - charging → 10 (screen on) / 16 (screen off)
   - battery ≥ 70 and screen on → **20** (`BATTERY_SCREEN_ON_INTERVAL_MINUTES`)
   - otherwise → null (loop stops)

   `shouldScheduleChargingLoop`, `postRunLoopAction` and the non-opportunistic branch of
   `shouldFetchNow` all derive from it (they each currently collapse to `isCharging`, so all three
   must change together or a scheduled run is policy-blocked on arrival, like the 14:21:10
   `policy_blocked` run in this report). They take `batteryLevel`.
3. **Callers** pass `batteryLevel`: `WidgetLoopScheduler`, `WidgetRefreshCoordinator.restartHeartbeats`,
   `UIUpdateReceiver`, and `CurrentTempUpdateScheduler.scheduleNextChargingUpdate`, which picks its
   interval from `loopIntervalMinutes`.
4. **Source scope on battery**: the battery-side loop fetches the **primary source only**, the same
   as the opportunistic job (`opportunisticTargetSourceId`). Only the charging loop fetches every
   visible source.
5. **Screen on (`ScreenOnReceiver.handleScreenOn`)**: on battery and ≥70%, start the loop. If the
   last current-observation fetch is ≥20 min old, fetch **now**, then every 20 min. Otherwise the
   first run is due 20 min after the last fetch. Without this catch-up, a typical <20-min phone
   session would never see a fetch, because the loop's first run is a delayed one.
6. **Screen off**: already cancels the loop on battery (`handleScreenOff`). No change.
7. **Logging**: loop reason `battery_screen_on_loop`, distinct from `charging_loop`, so the trace
   shows which cadence ran. `CURR_FETCH_LOOP_STOP` gains `battery=`.

### Desktop `:desktop` (AGENTS.md dual-platform rule)

`DesktopFetchStrategy.getObservationRefreshDelayMs`: on battery, `screenOn && battery >= 70` → 20 min
(currently 240 min via `INTERVAL_HIGH_MINUTES`). The screen-off/lower tiers are unchanged. Desktop
already has a screen-wake catch-up (`shouldCatchUpObservations`, 10 min).

### Docs

Add the 20-min row to the CLAUDE.md update table, and add a memory note
(`current_temp_cadence_is_a_gate_not_a_rate`).

## Tests

- `CurrentTempFetchPolicyTest`: `loopIntervalMinutes` table (charging on/off; battery 69/70/100 ×
  screen on/off). Check that `shouldFetchNow`, `postRunLoopAction` and `shouldScheduleChargingLoop`
  agree with it for every case, so a scheduled run is never policy-blocked.
- `CurrentTempUpdateScheduler` test: battery 75% + screen on → enqueues a 20-min delayed request.
  A last fetch ≥20 min ago → immediate.
- `ScreenOnReceiver` Robolectric: screen-on at 75% unplugged schedules the loop; at 69% it does not.
- `DesktopFetchStrategyTest`: 70/69 × screen on/off on battery.
- Update `LocationLatencyBudgetTest` table row.
- Unit suite: `scripts/unit-tests.sh`.

## On-device verification (Pixel 7 Pro)

`adb shell dumpsys battery unplug; adb shell dumpsys battery set level 75`, screen on: expect
`CURR_FETCH_WORK_REQUESTED reason=battery_screen_on_loop delayMinutes=20` and fetches about 20 min
apart. `set level 69` → `CURR_FETCH_LOOP_STOP`. Screen off → loop cancelled. Finally
`adb shell dumpsys battery reset`.

## Cost

Worst case, with the screen on continuously on battery: 3 primary-source fetches/hour instead of
~1.3. The fetch is one NWS call per nearby station (5) over a radio that is already awake because
the screen is on. With the screen off, nothing changes.

## Revision after on-device test (approved 2026-10-03)

**Delayed WorkManager runs did not fire.** The first build timed the battery loop with a 15-minute
delayed WorkManager request. On the Pixel, JobScheduler held it at `Ready: true` (every constraint
satisfied, `Restricted due to: none`, standby bucket ACTIVE) from 15:01:39 to past 15:14, while
other apps' jobs ran. The non-wakeup `ui_update_alarm` fired on time over the same period. The
morning trace shows the same lateness while charging (12:15 and 13:07 runs needed the
`charging_loop_overdue` repair).

**Change:** on battery, `scheduleNextChargingUpdate` arms `BatteryObservationAlarm` (`RTC`,
non-wakeup) instead of enqueueing delayed work. `BatteryObservationAlarmReceiver` re-checks the
policy when it fires, enqueues an *immediate* primary-only request, and re-arms. The charging loop
is unchanged.

- **Never postponed:** a pending alarm is kept if it is earlier than the one requested, so the
  15–60 min `ui_update_alarm` heartbeat can't keep pushing the fetch back.
- **Window:** there is no exact-alarm permission, so Android 12+ delivers no tighter than a 10-minute
  window (`setAndAllowWhileIdle` got +13.5 min). User's choice: a 10-minute `setWindow` centred on
  the nominal time, so fetches land **15–25 min** apart. Rejected: 20–30 min, and requesting
  `SCHEDULE_EXACT_ALARM`.
- **Screen-off** on battery cancels the alarm too.
- **Primary only, enforced in the worker:** any non-manual run on battery fetches only the primary
  source, even a charging-loop request that was enqueued before the unplug.
- **Logs:** `CURR_FETCH_WORK_REQUESTED type=battery_alarm decision=armed|kept_earlier`, and
  `BATTERY_OBS_ALARM outcome=fetch_enqueued|loop_ended`.
- **Tests:** `BatteryObservationAlarmTest`, `BatteryObservationAlarmReceiverTest`, plus alarm
  assertions in `CurrentTempUpdateSchedulerTest`.

## Revision 2: Adaptive Battery Saver (approved 2026-10-03: "run anyway")

**Ordinary alarms were deferred a year.** The `setWindow` alarm never fired off-charger:
`dumpsys alarm` showed `battery_saver=+364d` on it and on every other background app's plain alarm.
Pixel's **Adaptive Battery Saver** follows the charger on this phone: ADAPTIVE at 15:40:08 (two
seconds after unplug), OFF at 16:04:20 (re-plug), 27 activations since boot. The Settings toggle
stays off. Its policy includes `force_all_apps_standby=true`, which holds back background apps'
plain alarms and jobs while the screen is on too; a home-screen widget does not make the app
active. This very likely also explains the stalled delayed WorkManager run in revision 1.

**Change:**
- **Alarm:** `setAndAllowWhileIdle`, which is exempt. The system gives it a window of 75% of the
  delay, so the trigger sits at (nominal + 5 min) / 1.75: a 20-minute loop is delivered about
  14.3–25 min out.
- **Fetch:** the receiver and the screen-on catch-up enqueue it as expedited (API 31+,
  `RUN_AS_NON_EXPEDITED_WORK_REQUEST`), which Battery Saver does not defer.
- **Bug fix:** a pending alarm counts until its window closes, not until its trigger time. A
  heartbeat at 16:00:30 had replaced a 15:58 alarm whose window ran to 16:03.

**Verified under ADAPTIVE:** the new alarm shows `battery_saver=-524ms` (not deferred) and a window
of `+6m51s` (= 0.75 × 548 s).

## Integration tests (approved 2026-10-03)

Each bug found today sat between classes; the per-class tests pass with all of them present.

1. **`BatteryScreenOnObservationLoopIntegrationTest` (Robolectric).**
   - Setup: two simulated hours on battery at 75% with the screen on, on a fake clock, with
     WorkManager captured.
   - Drives the real `ScreenOnReceiver` → `CurrentTempUpdateScheduler` → `BatteryObservationAlarm`
     → `BatteryObservationAlarmReceiver` → `WidgetLoopScheduler` post-run chain.
   - Interleaves a `ui_update_alarm` heartbeat every 15 min.
   - Asserts: fetches 14–25 min apart, each NWS-only and expedited, never postponed by a
     heartbeat. Plugging in or < 70% ends the chain; screen-off cancels the alarm.
   - Small test seams: an injectable clock on the two receivers (same pattern as their
     `ioDispatcher`) and a `nowMs` parameter on `manageCurrentTempLoopAfterRun`.
2. **`scripts/check-battery-saver-alarm.sh` (device check, emulator).** The planned instrumented
   test could not work: instrumentation runs inside the app's process and keeps it "active", which
   exempts every alarm. Its own control alarm, a plain `set()`, showed `battery_saver=-14ms`, so the
   test was deleted.
   - The script drives the installed app from the host: unplug at 75%, the app arms its alarm on
     screen-on, wait until the app is inactive, then turn on full Battery Saver
     (`cmd power set-mode 1`), which re-evaluates every alarm.
   - The emulator's stock adaptive policy has `force_all_apps_standby=false`, so full mode stands in
     for the Pixel's adaptive mode.
   - It requires at least one other alarm to be deferred `+…d` (the control).
   - Verified both ways: the real build PASSes (`battery_saver=-1m21s`); a `setWindow` build FAILs
     (`battery_saver=+364d`).
   - The Robolectric test (5 cases) fails with `got 1` fetches when the pending-window bug is put
     back.
