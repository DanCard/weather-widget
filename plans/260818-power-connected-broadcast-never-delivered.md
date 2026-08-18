# Plug-in never refreshes: ScreenOnReceiver has never fired on any device

## Problem

Reported: "samsung: when newly plugged and display on should refresh current temps. Current
observations stale by more than an hour."

## Root cause

`ScreenOnReceiver` is a **manifest-declared** receiver for three *implicit* broadcasts —
`ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, `USER_PRESENT`. The app targets SDK 36.
Since Android 8.0 (API 26) manifest-declared receivers are not delivered implicit broadcasts
unless the action is on the framework exemption list, and **none of these three are**. The class
has therefore never run, on either device.

### Evidence (2026-08-18)

- Samsung SM-F936U1: 56,754 `app_logs` rows spanning 2026-08-15 → 2026-08-18.
  `POWER_CONNECTED_EVENT` = **0 rows**. `UNLOCK_REFRESH_POLICY` = **0 rows**.
- Pixel 7 Pro: 20,980 rows over the same span. `POWER_CONNECTED_EVENT` = **0 rows**.
- `GPS_RESAMPLE` only ever carries `trigger=worker` — never `power_connected` / `user_present`,
  the two triggers only `ScreenOnReceiver` emits.
- Live test on the Samsung: `dumpsys battery unplug` → `dumpsys battery reset` produced a genuine
  system dispatch (`BatteryService: sendBroadcastToExplicitPackage: Intent { act=
  android.intent.action.ACTION_POWER_CONNECTED } -> com.samsung.android.sm.devicesecurity`) and
  our receiver did not run — with the app **process alive** (pid 31295) and an activity in the
  foreground. So this is not process-not-running, not Doze, not Samsung app-sleep.
- `dumpsys package` confirms the filter is registered and `enabled=0` (default/enabled),
  `stopped=false`; `am get-standby-bucket` = 5 (EXEMPTED). Nothing is throttling the app.

### Why the earlier fix did not take

`plans/260413-no-refresh-when-plugged-in-and-charging.md` diagnosed the same dead receiver but
attributed it to `android:exported="false"` and flipped it to `"true"`. The manifest is now
`exported="true"` and the receiver is still dead — `exported` was never the gate. That plan
already noted the API-26 implicit-broadcast restriction for `ACTION_SCREEN_OFF` but did not
apply the same reasoning to `ACTION_POWER_CONNECTED` / `USER_PRESENT`.

## Consequence

Plugging in does none of what `handlePowerConnected` promises: no immediate current-temp fetch,
no re-scheduling of the periodic sync at charging cadence, no `NonPrimaryObservationScheduler`
start, no GPS resample. The 10-minute charging loop is a self-perpetuating chain that dies
whenever an iteration fires unplugged (`CURR_FETCH_SKIP reason=charging_loop policy_blocked
charging=false battery=76 cutoff=65` at 13:13:52, then `CURR_FETCH_LOOP_STOP ...
action=no_reschedule`) and nothing plug-driven ever restarts it. The only surviving restarts are
the `UIUpdateReceiver` alarm heartbeat and user interaction, so in practice current observations
refresh only via the 45-minute `OpportunisticUpdateJobService` — hence staleness > 1 hour.

## What will change

Replace the broadcast trigger with a **JobScheduler charging constraint**, which is the
documented, delivery-reliable replacement and is already the pattern used by
`OpportunisticUpdateJobService` in this codebase.

1. **New `PowerConnectedJobService`** (`JOB_ID = 1003`), a one-shot `JobInfo` with
   `setRequiresCharging(true).setPersisted(true)` plus a minimum latency on re-arm.
   - Unplugged: sits pending with the charging constraint unsatisfied, so it fires **promptly on
     plug-in** — the "newly plugged" edge the user asked for.
   - After running it re-arms itself with `setMinimumLatency(CHARGING_INTERVAL_MINUTES)` so that
     staying plugged in cannot spin (a 0-latency re-arm while charging would re-fire instantly).
     While charging this doubles as heartbeat recovery for the charging loop.
   - `ensureScheduled()` no-ops when `getPendingJob(JOB_ID) != null`, so repeated calls from
     widget updates do not reset a pending trigger.
2. **Extract the plug-in body** out of `ScreenOnReceiver.handlePowerConnected` into a shared
   `PowerConnectedRefresh` object so the receiver and the job run one implementation. The
   existing 2-minute debounce, `POWER_CONNECTED_EVENT` logging (now tagged with `source=`), and
   `NonPrimaryObservationScheduler` screen-interactive gate are preserved as-is.
3. **Arm the job** everywhere `scheduleOpportunisticUpdate` is already armed:
   `WeatherWidgetApp.onCreate`, `WeatherWidgetProvider`.
4. **Leave `ScreenOnReceiver` and its manifest entry in place.** It is harmless, its unit tests
   stay green, and it still works for any OEM that does deliver the broadcast.

## Verification

- Unit: new `PowerConnectedJobServiceTest` (arm/no-op-when-pending/re-arm latency) and
  `PowerConnectedRefreshTest` (debounce outcome), plus existing `ScreenOnReceiverTest` unchanged.
- On-device: `dumpsys battery unplug` → `reset` on the Samsung, then assert a
  `POWER_CONNECTED_EVENT source=job` row and a fresh `CURR_FETCH_START` / `OBS_CURRENT_INSERT`
  within minutes; `adb shell dumpsys jobscheduler | grep -A5 weatherwidget` to see the pending
  charging-constrained job.

## Implementation notes

- **Re-arm must happen after `jobFinished`, not before.** The first on-device run re-armed inside
  `onStartJob` and JobScheduler treated scheduling `JOB_ID` while that same job was running as a
  *replacement*: `W JobScheduler: Job didn't exist in JobStore: ... PowerConnectedJobService`,
  followed by `onStopJob`, which cancelled the coroutine before it wrote its log row or resampled.
  The re-arm now runs in the coroutine's `finally`, after `jobFinished(params, false)`.
- **`onStopJob` returns `false`.** Returning `true` asks JobScheduler to reschedule with backoff;
  with the charging constraint already satisfied that re-runs almost immediately. The explicit
  latency-carrying re-arm is the only intended path back.

## Status

Implemented 2026-08-18. `./gradlew :app:testDebugUnitTest` green (full suite).
`PowerConnectedJobServiceTest` (7 tests) and the unchanged `ScreenOnReceiverTest` pass; the two
load-bearing assertions (charging constraint, no-op re-arm) were each confirmed to fail when the
behaviour is inverted.

## Follow-ups (not in this change)

- **`USER_PRESENT` is dead for the same reason** — the screen-unlock refresh
  (`handleUserPresent`, `UNLOCK_REFRESH_POLICY`) has also never run. There is no JobScheduler
  screen-on constraint, so recovering it needs a different mechanism (dynamic registration from a
  live component, or leaning on widget-interaction paths). Flagged, not fixed here.
- Re-arm latency means a quick unplug/replug inside the 10-minute window is not caught promptly.
