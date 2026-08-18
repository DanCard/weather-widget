# Plug-in never refreshes: ScreenOnReceiver has never fired

## Problem

Reported: "samsung: when newly plugged and display on should refresh current temps. Current
observations stale by more than an hour."

The cause is larger than the plug-in path. **`ScreenOnReceiver` has never fired at all, on any
device.** All three actions it is manifest-registered for — `ACTION_POWER_CONNECTED`,
`ACTION_POWER_DISCONNECTED`, `ACTION_USER_PRESENT` — are *implicit* broadcasts, and an app
targeting API 26+ (this app is `targetSdk = 36`) is not delivered those through a
manifest-declared receiver unless the action is on the framework exemption list. None of them are.

Evidence, not inference:

| Check | Result |
|---|---|
| Samsung `app_logs`, 2026-08-15 → 08-18, 56,754 rows | **0** `POWER_CONNECTED_EVENT`, **0** `UNLOCK_REFRESH_POLICY` |
| Pixel 7 Pro `app_logs`, same span, 20,980 rows | **0** `POWER_CONNECTED_EVENT` |
| `GPS_RESAMPLE` triggers | only ever `trigger=worker` — never `power_connected` / `user_present`, the two only this receiver emits |
| Live `dumpsys battery unplug` → `reset` on the Samsung | system dispatched it (`BatteryService: sendBroadcastToExplicitPackage: Intent { act=android.intent.action.ACTION_POWER_CONNECTED }`); receiver did not run |

That last test ran with the process **alive** (pid 31295) and an activity foregrounded, standby
bucket 5 (EXEMPTED), `stopped=false`, `enabled=0`. So it is not Doze, not Samsung app-sleep, and
not a cold process refusing to start.

**`exported="true"` is not the fix, and was already tried.**
`plans/260413-no-refresh-when-plugged-in-and-charging.md` diagnosed the same dead receiver, blamed
`android:exported="false"`, and flipped it. The manifest is `exported="true"` today and the
receiver is still dead — `exported` was never the gate. That plan even noted the API-26 implicit
broadcast restriction for `ACTION_SCREEN_OFF`, but did not carry the same reasoning across to
`ACTION_POWER_CONNECTED` / `USER_PRESENT`.

The receiver *looks* alive in `dumpsys package`: the intent filter is registered and matched.
Registration and delivery are separate concerns — the ban filters at dispatch and leaves no trace
at the receiver end, which is why two rounds of source reading missed it.

### Why observations go stale past an hour

The 10-minute charging loop is a self-perpetuating chain: an iteration that fires while unplugged
is policy-blocked and deliberately does not reschedule its successor. The Samsung shows exactly
that at 13:13:52 —

```
CURR_FETCH_SKIP    reason=charging_loop policy_blocked charging=false battery=76 cutoff=65 ...
CURR_FETCH_LOOP_STOP reason=policy_blocked plugged=false interactive=false action=no_reschedule
```

Nothing plug-driven ever restarts it, because the only plug-driven thing was the dead receiver.
Surviving restarts are the `UIUpdateReceiver` alarm heartbeat and user interaction, so in steady
state current observations refresh only via the 45-minute `OpportunisticUpdateJobService`.
`OBS_CURRENT_INSERT` and `CURR_FETCH_START` both stop at 12:52 while work keeps being *requested*
through 13:25 — the worker runs and skips.

The codebase already half-knew: `UIUpdateReceiver.kt:49` carries the comment *"handles cases where
`ACTION_POWER_CONNECTED` was missed by the OS"* — a workaround built around a symptom whose cause
was "always", not "sometimes".

## What changed

Scope chosen by the user: **plug-in only**.

**New `PowerConnectedJobService.kt`** — a JobScheduler charging constraint, which *is* delivered to
background apps, standing in for the undelivered broadcast. One-shot rather than periodic so it
fires on the *transition*: `setRequiresCharging(true).setPersisted(true)`, `JOB_ID = 1003`.
Unplugged it parks with the charging constraint unsatisfied; the moment power returns JobScheduler
dispatches it.

- It re-arms itself with `setMinimumLatency(CHARGING_INTERVAL_MINUTES)`. That latency is
  load-bearing: a zero-latency re-arm while still charging would find its constraint already
  satisfied and re-fire immediately, forever. With it, the still-charging case degrades into a
  harmless heartbeat at the charging-loop cadence — which doubles as recovery for a dead loop —
  while the unplugged case keeps firing the instant power returns.
- `ensureScheduled()` is a no-op when `getPendingJob(JOB_ID) != null`. Callers include widget
  lifecycle paths that fire often, and overwriting would reset the latency clock on a trigger that
  is already waiting for the charger. The first arm carries no latency, so a plug-in seconds later
  is still caught at once.

**New `PowerConnectedRefresh.kt`** — the plug-in body extracted out of `ScreenOnReceiver` so the
receiver and the job run one implementation. The 2-minute debounce, the `POWER_CONNECTED_EVENT`
row (now carrying `source=job` vs `source=broadcast`), and the screen-interactive gate on
`NonPrimaryObservationScheduler` are preserved.

**Armed** from `WeatherWidgetApp.onCreate` and `WeatherWidgetProvider.onEnabled`, alongside the
existing `scheduleOpportunisticUpdate` calls. Registered in the manifest with
`BIND_JOB_SERVICE`.

**`ScreenOnReceiver` kept**, delegating to the shared body. It costs nothing and still works
wherever the broadcast *is* delivered. Its KDoc now states plainly that it does not fire, with the
evidence and a pointer to the plan, so this is not chased a third time.

### Two defects found while verifying on-device

1. **Re-arm must come after `jobFinished()`.** The first on-device run re-armed inside
   `onStartJob`, and JobScheduler treated scheduling `JOB_ID` while that same job was running as a
   *replacement*: `W JobScheduler: Job didn't exist in JobStore: ... PowerConnectedJobService`,
   followed by `onStopJob`, which cancelled the coroutine before it wrote its log row or resampled
   location. The re-arm now runs in the coroutine's `finally`, after `jobFinished(params, false)`.
   `onStopJob` also returns `false` now — returning `true` asks JobScheduler to reschedule with
   backoff, which with the charging constraint already satisfied re-runs almost immediately.
2. **The plug-in refresh was flagged `opportunistic = true`.** That path is gated on
   `batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT` (65) and **ignores charging entirely**, so it
   would have been blocked at exactly the battery levels that make people reach for a charger. Now
   `opportunistic = false`, which gates on `isCharging` — true by construction, since this only
   runs off a charging transition. The flag was moot while the broadcast was undelivered; it stops
   being moot now.

## Verification

- `./gradlew :app:testDebugUnitTest` green across the full suite.
- New `PowerConnectedJobServiceTest` (8 tests): charging constraint, persistence, zero-latency
  first arm, latency-carrying re-arm, arm-when-absent, no-op-when-pending, no network requirement,
  and that a plug-in refresh is not blocked by the opportunistic battery cutoff.
- The two load-bearing assertions were each confirmed to **fail** when the behaviour is inverted
  (`setRequiresCharging(false)`; pending-check disabled). The no-op-when-pending test was rewritten
  after the first version passed under a disabled check — it now stands the pending job up as a
  latency-carrying re-arm, which an overwrite would flatten to zero.
- Existing `ScreenOnReceiverTest` unchanged and passing.
- On-device (Samsung SM-F936U1): job appears in `dumpsys jobscheduler` as
  `JOB #u0a517/1003 ... Requires: charging=true`, `PERSISTED`; parks correctly while unplugged with
  `Unsatisfied constraints: CHARGING TIMING_DELAY`; survives reinstall and logs
  `Plug-in trigger already armed`. Plug-in transition timing confirmed separately.

## Follow-ups

- **`ACTION_USER_PRESENT` is dead for the same reason** — the "display on" half of the original
  report. The screen-unlock refresh (`handleUserPresent`, `UNLOCK_REFRESH_POLICY`) has never run.
  There is no JobScheduler screen-on constraint, so recovering it needs a different mechanism
  (dynamic registration from a live component, or leaning on widget-interaction paths). Left out by
  the user's scope choice.
- The re-arm latency means a quick unplug/replug inside the 10-minute window is not caught
  promptly.
- `UIUpdateReceiver.kt:49`'s "missed by the OS" heartbeat-recovery call is now redundant belt-and-
  braces rather than the only thing holding the charging loop up. Worth revisiting once the job has
  proven itself in daily use.

Detailed plan: `plans/260818-power-connected-broadcast-never-delivered.md`.
