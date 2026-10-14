# Current-temp "immediate" work queues behind the delayed charging loop

**Reported (2026-10-14):** Pixel 7 Pro slow to update actual temperature readings.

## Evidence (Pixel backup `backups/20261014_123539_pixel_7_pro_2A191FDH300PPW`)

Every `CURR_FETCH_WORK_ENQUEUED type=immediate` matched to its `CURR_FETCH_WORK_START`:

| Enqueued | Reason | Started |
|---|---|---|
| 10-13 16:24 | user tap (`stale_on_refresh_action_cache_first`) | never |
| 10-13 16:44 | `battery_screen_on_catchup` (expedited) | never |
| 10-14 01:15 | user tap | 02:35 (+80 min) |
| 10-14 06:15 | user tap | 09:15 (**+3 h**) |
| 10-14 07:47 | `opportunistic_job` | 11:18 (+3.5 h) |
| 10-14 08:32 … 11:50 | 4 × opportunistic, 2 × user tap | never (as of 12:35) |

The delayed loop request `5a26e582` was requested at 07:30 "due 07:46" and ran at 11:18.
On charger the loop still fetches about every 16 min, but no tap, screen-on catch-up or opportunistic
request runs when it is asked for. On battery the catch-up never runs, and the next fetch waits
40–68 min.

## Root cause

All current-temp work shares one unique name, `WORK_NAME_CURRENT_TEMP`, and enqueues with
`APPEND_OR_REPLACE`. WorkManager's APPEND makes the new request a **dependent of the chain's last
item**, so it does not run beside what is there:

1. The charging loop enqueues a request with `setInitialDelay(16 min)` and APPEND_OR_REPLACE.
2. An "immediate" request (tap, opportunistic job, catch-up, Settings refresh) is appended **behind**
   it (state BLOCKED), so it waits for the delay to elapse and for that request to run.
3. `decideChargingLoopWork` → `activeCurrentTempWork` considers only RUNNING / ENQUEUED and
   ignores BLOCKED. After each run it therefore sees `no_active_work` and appends **another** delayed
   item at the end of the chain. The immediate worker that runs next does the same, so you get two
   `CURR_FETCH_WORK_REQUESTED` per cycle in the log.
4. The chain grows into delayed → immediate → delayed → … and each added link costs about 16 min.
   Latency grows without bound for as long as immediates keep arriving.

APPEND was chosen so a running worker is never cancelled (`[[samsung_widget_dead_native_sigsegv]]`).
That reason still holds. The defect is that a delayed heartbeat and a run-now request share one chain.

## Fix

1. **Split the names.** Immediate requests use a new name, `WORK_NAME_CURRENT_TEMP_NOW`, which
   carries no delayed items:
   - `CurrentTempUpdateScheduler.enqueueImmediateUpdate`
   - `SettingsActivity` Refresh data
   - ~~the charging loop's `REPLACE_IMMEDIATE`~~ **stays on the loop name** (changed during
     implementation). On the run-now name the overdue heartbeat would stay in the loop chain, and each
     run's post-run reschedule would see it again and enqueue another run-now item: a spin.

   Keep APPEND_OR_REPLACE on that name. It still never cancels a running worker, and a chain of
   zero-delay items drains in seconds; back-to-back duplicates hit the existing 5-min
   `CURR_FETCH_FRESH_SKIP`. The delayed heartbeat stays alone on `WORK_NAME_CURRENT_TEMP`.
2. **Count BLOCKED as active** in `activeCurrentTempWork`, so the loop never appends a second
   heartbeat behind an unfinished one. This is defence in depth, and it also drains a chain that
   already exists on a device when the update installs.
3. **One-shot drain on upgrade.** No cancel is needed: with (2), the existing over-long chain is not
   extended, and the loop's REPLACE_DELAYED / `too_far_future` path recovers it. Verify this on the
   Pixel. If it does not recover, use `cancelUniqueWork` only from a non-worker context
   (app start), never from inside a worker.
4. **Every cancel and observer of the old name follows:**
   - `CurrentTempUpdateScheduler.cancel` and `WeatherWidgetProvider` cancel both names.
   - `SettingsActivity.observeRefreshCompletion` watches the new name.
5. **Concurrency.** A run-now worker can now overlap a loop worker. Both go through the same
   freshness check, so the second becomes `CURR_FETCH_FRESH_SKIP`. Confirm there is no shared
   mutable state that assumes one current-temp worker at a time (WeatherWidgetWorker current-temp
   path, `CurrentTempFetchPolicy.PostRunLoopAction` doc).

## Tests

- Unit: `decideChargingLoopWork` with a BLOCKED item → KEEP (not ENQUEUE_DELAYED).
- Robolectric + `WorkManagerTestInitHelper` (integration: scheduler + WorkManager):
  - enqueue a delayed loop item, then `enqueueImmediateUpdate`. The immediate request is ENQUEUED
    (not BLOCKED) and runs without advancing the delay.
  - two loop reschedules in a row never leave more than one unfinished heartbeat.
- Device (Pixel): after install, tap the widget. `CURR_FETCH_WORK_START` for that workId follows
  within seconds, and the enqueue→start matcher above shows no NEVER / multi-hour rows over a day.

## Result (2026-10-14, Pixel, debug build installed 12:44)

- The post-install `opportunistic_job` (`2a81c7e1`) went onto `weather_widget_current_temp_now` and
  fetched at 12:44:38, while the loop heartbeat `50bb6a8f` was still waiting until 12:46. Before
  the fix it would have been appended behind that heartbeat.
- The 12:46 heartbeat logged `decision=keep reason=blocked_successor` and appended nothing. Before
  the fix this logged `enqueue_delayed reason=no_active_work` here.
- WorkManager DB at 12:45: loop chain = 1 ENQUEUED + 1 BLOCKED (left over from before), run-now
  chain = 1 SUCCEEDED. The leftover drains one heartbeat per run.
- Tests: `CurrentTempUpdateSchedulerTest` (+2 BLOCKED cases; the immediate tests now use the
  run-now name) and `CurrentTempRunNowQueueIntegrationTest` (a real test WorkManager: run-now is
  ENQUEUED with no delay beside a 16-min heartbeat; cancel clears both names). Both fail against the
  old code.
- Still to do: rerun the enqueue→start matcher over a day of logs. Expect no NEVER / multi-hour rows.

## Not in scope

- The 10-13 17:04 battery alarm never logged a fire (`BATTERY_OBS_ALARM`). That is likely Adaptive
  Battery Saver deferral (`[[pixel_adaptive_battery_saver_follows_charger]]`). Re-check after this
  fix; the screen-on catch-up should cover it once it can actually run.
- `NonPrimaryObservationScheduler` (checked 2026-10-14): **no queue growth possible.** Only delayed
  loop runs are ever added under its name, nothing run-now, and a RUNNING item gives KEEP. Pixel 48 h:
  3 schedules, 30 cancels, no chain (WorkManager DB: 1 CANCELLED). The cancels are by design:
  `NonPrimaryObservationPolicy` runs it only while charging + screen on (30 min), and screen off
  cancels it. Latent and unfixed: `WidgetLoopScheduler.manageNonPrimaryLoopAfterRun` passes no
  `ignoreRunningWorkId`, so a non-primary worker's post-run reschedule can see itself RUNNING → KEEP
  and end the loop after one run. Unconfirmed on device (no non-primary run in 48 h).
