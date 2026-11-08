# Samsung Freecess stalls the now-dot age and the observation loop

Date: 2026-11-08. Status: implemented (job-based tick).

## Report

Fold 4 (RFCT70G352Y): "the 15 minutes on now dot doesn't seem to be advancing."

## Evidence (Fold 4, 2026-11-08)

Screenshot at 11:37: the hourly widget (id 392) shows `knuq 68° @ 11:15 am` and the fetch-dot age
`15m`. The last paint of widget 392 was 11:30:12 (`WIDGET_PAINT … origin=ACTION_REFRESH`). That paint
was correct for 11:30 (11:15 + 15 min). Nothing painted it after that. The screen was on and the phone
was on USB power (app sees `charging=true`).

### 1. The 2-minute UI repaint alarm is held by Freecess

`UIUpdateScheduler` sets a non-wakeup `RTC` alarm with `setAndAllowWhileIdle`. Samsung's Freecess freezes
the app a few seconds after each interaction, then holds the app's non-wakeup alarms until something
else unfreezes it:

| Time | Event |
|---|---|
| 11:30:07.580 | `UFZ : com.weatherwidget reason: broadcast-…ACTION_CYCLE_ZOOM` (user tap); `Sending alarms blocked by MARsFreecess` |
| 11:30:07.955 | `UI update alarm scheduled for 120 seconds from now` |
| 11:30:13.596 | `FZ : com.weatherwidget reason: Bg [cached` |
| 11:32:55.189 | `AlarmManager: Deferring alarm … type 1 … com.weatherwidget due to freecess` |
| 11:38:32 | `dumpsys alarm`: no delivery after 11:30:07; the alarm is not in the pending list |

The same thing happened all morning (09:02, 09:52, 10:33, 10:47, 11:15, 11:26, 11:32). Each held alarm
was released only by a screen-on, a widget tap or a `SCREEN_OFF` broadcast. All nine Freecess
deferrals in the buffer were non-wakeup alarms (types 1 and 3). `UFZ … reason: job` appears for other
apps, so a JobScheduler job does unfreeze a frozen app.

### 2. The charging observation loop waits forever on CONNECTIVITY

The 10-minute current-temp loop job (WorkManager, `NetworkType.CONNECTED`) was due at 11:31 and still
not running at 11:41:

- `dumpsys jobscheduler`: `Satisfied: TIMING_DELAY …`, `Unsatisfied constraints: CONNECTIVITY`,
  `Run time: earliest=-9m27s`, `Standby bucket: ACTIVE`.
- `dumpsys netpolicy`: `UID=10365 … blocked=APP_BACKGROUND`. `dumpsys connectivity`:
  `UID=10365 blockedReasons=c0`, which is APP_BACKGROUND (0x40) plus OEM_DENY (0x80).
- Constraint history: CONNECTIVITY becomes true only during the seconds the app is unfrozen
  (11:30:07 → 11:30:13).

So the observation behind the dot also stops refreshing until the user touches the phone. That is why
the dot still shows the 11:15 KNUQ reading.

## Root cause

Freecess freezes the idle widget process. While it is frozen:

1. Non-wakeup alarms are held, so the UI tick that advances the age label, Now line and dot never fires.
2. The uid's network is OEM-blocked, so JobScheduler never considers a `CONNECTED` job ready.

Before, the app's own activity masked this. Taps, screen-on events and `SCREEN_OFF` broadcasts each
unfroze it.

## Proposed fix (Android only; desktop is unaffected)

1. **Verify first (throwaway debug build, not committed):** on the Fold, schedule the UI tick as
   `RTC_WAKEUP` while frozen. Confirm whether Freecess delivers it (`UFZ … reason: alarm…` and
   `UI update alarm triggered` on time) or defers it too.
2. **UI tick:**
   - If step 1 passes: while the screen is interactive, use `RTC_WAKEUP` in
     `UIUpdateScheduler.scheduleUpdate`. Keep `RTC` with the screen off. The device is already awake,
     so screen-on wakeups cost nothing, and screen-off cadence is unchanged.
   - If step 1 fails: move the screen-on tick to a delayed unique `OneTimeWork` with no network
     constraint (`REPLACE` is allowed for delayed work). Jobs unfreeze the app (`UFZ reason: job`).
   - Log the type of each scheduled tick (`UI_TICK_SCHEDULED type=… delayMs=…`, VERBOSE).
3. **Observation loop:** drop `NetworkType.CONNECTED` from the delayed current-temp loop work
   (`CurrentTempUpdateScheduler` ~line 270). Do the same for the non-primary loop if it shows the same
   stall. Starting the job unfreezes the app and lifts the background firewall. The worker then checks
   for a validated network. Without one, it logs `CURRENT_TEMP_SKIP reason=no_network` and reschedules
   at the normal interval, without burning a fetch. Immediate work keeps its constraint.
4. Update `arch/hourly-forecast-update-cadence.md` (Android cadence section) and `arch/operations.md`.
   Add a Freecess diagnosis note with the `Deferring alarm … due to freecess` and `blockedReasons`
   tells.

## Tests

- Unit (Robolectric): `UIUpdateScheduler` picks the alarm type from screen interactivity (shadow
  AlarmManager records the type).
- Unit: the current-temp loop request has no network constraint; the worker's no-network branch skips
  and reschedules without calling the fetcher.
- Device: Fold 4 on USB with the screen on and no touches for 15 min. The age label should advance about
  every 2 min, and `SYNC_START reason=…loop` should run every ~10 min while `FZ` entries continue.

## Outcome (2026-11-08)

- Steps 1–2 implemented directly as `RTC_WAKEUP` while the screen is on, logged as `UI_TICK_SCHEDULED`. On the
  Fold the tick fired every 2–3.5 min (inexact window) between 11:58 and 12:04.
- **Step 3 reverted.** Without `CONNECTED`, the loop job ran at 12:03:45 with
  `blockedReasons=40` (Android's app-background restriction, no Freecess freeze) and found no network
  (`CURR_FETCH_SKIP no_network waitedMs=5000`). JobScheduler lifts that block for a running job only
  when the job declares a network constraint. The loop keeps `CONNECTED`, with a unit-test guard. While
  frozen (`0xc0`), the job becomes ready when the screen-on wakeup tick unfreezes the app.
- **Step 1 failed; RTC_WAKEUP reverted.** Clean soak (screen kept on, no touches, 12:07–13:36): frozen at
  12:17:34 (`FZ … Bg [cached`). `Deferring alarm … type 0 … due to freecess` at 12:18, 12:56 and 13:33,
  so Freecess holds wakeup alarms too. The ticks were released only by `UFZ reason: job` at 12:52:59
  and by the system's 30-minute `APPWIDGET_UPDATE` at 13:30:02. The age label was stale for 35 minutes
  at a time.
- Remaining options: (a) a no-constraint delayed WorkManager tick (untested: a job did unfreeze the app at
  12:52, but the frozen `CONNECTED` loop job waited 35 min); (b) exempt the app from battery
  optimization, since `com.stock.widget` is on the user exemption list and its 2-minute alarms are delivered.
- **Option (a) chosen and verified.** The screen-on tick is a delayed unique `UiTickWorker` job (`ui_tick`),
  and the screen-off tick stays an `RTC` alarm. Soak from 14:00 to 14:12 with the app frozen (`FZ`) after every run
  and no touches: ticks ran at 14:00:58, 14:03:14, 14:05:16, 14:07:31 and 14:10:02, each preceded by
  `UFZ … reason: job`. The charging loop ran on time at 14:05:26 (the tick's unfreeze satisfies its
  `CONNECTED` constraint). Tests: `UiTickSchedulingTest` and the loop-constraint guard in
  `CurrentTempUpdateSchedulerTest`.
