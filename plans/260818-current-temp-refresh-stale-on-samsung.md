# Current temp refresh stale on Samsung (2026-08-18)

## Report

"Auto update on samsung seems to have broken. 10 minute refresh of current observations doesn't
seem to be working. Last 3 times I checked it has been stale."

## What the cadence actually is

There is no single "10 minute refresh". Battery level is an on/off gate, not a rate:

| Condition | Interval |
|---|---|
| Charging, screen on | 10 min (`CHARGING_INTERVAL_MINUTES`) |
| Charging, screen off | 16 min (`CHARGING_SCREEN_OFF_INTERVAL_MINUTES`) |
| On battery, >65% | 45 min (`OPPORTUNISTIC_INTERVAL_MINUTES`) |
| On battery, <=65% | none beyond the 60-480 min data fetch |

At 80% *unplugged* the answer is 45 minutes, and it does not get faster at 90% or 100%. Confirmed
in the logs: opportunistic runs at 12:05:53 -> 12:52:40 -> 13:35:55 (47 and 43 min apart). Add NWS
observation lag (`observedAt` typically trails 5-20 min) and "stale by more than an hour" is the
expected on-battery behaviour, not a fault.

## A wrong turn, recorded deliberately

The first diagnosis was that Samsung's Protect Battery mode (`pbm=max`) reports
`plug=none status=discharging` while holding the charge cap, defeating every branch of
`BatteryStatePolicy.isEffectivelyCharging`. The cited evidence was a `dumpsys batterystats`
sample at 15:15:27 showing exactly that while the phone was said to be on a charger.

**That was a misread.** Samsung's `ACTION_BATTERY_CHANGED` history shows:

```
14:34:08 -> 15:15:27   ac=true  online=38   (dedicated charger)
15:15:27              ac=false online=1     (3-second gap, cable swap)
15:15:30 ->           usb=true online=4     (laptop USB)
```

`ac=true` sets `EXTRA_PLUGGED = BATTERY_PLUGGED_AC`, so `isEffectivelyCharging` returned **true**
for the whole time the phone was on the charger, and the 10-minute loop duly ran at 14:45, 14:55,
15:05 and 15:15. The "smoking gun" sample was the three seconds while the cable was being moved.

## What is actually unexplained

Between **13:51:59 and 14:06:29** the JobScheduler charging constraint fired (POWER_CONNECTED_EVENT
at 13:51:59) while the app's own sticky-broadcast read reported `plugged=false` (13:56:03, 14:06:29),
and the battery level rose **76 -> 78** across the window. A rising level is not a discharging
device. The framework and the sticky broadcast disagreed; the cause is not established.

## Changes

### 1. Infer charging from a non-falling high battery level

Requested rule: infer charging from a battery level that is not dropping at 78% or above.

- `BatteryTier.HELD_CHARGE_MIN_LEVEL = 78` and `BatteryTier.inferChargingFromLevelTrend(...)` (pure).
- `BatteryChargeTrend` persists the two values that decision needs across process death.
- `BatterySnapshotProvider` ORs it with the platform answer, so the platform result is a floor.

Rules: below 78 never infer; a rise means charging; a drop means discharging; a plateau keeps the
previous verdict and counts as charging before any verdict exists.

Both plateau rules are load-bearing:

- **A plateau must count as charging**, or the rule cannot latch in the case it exists for. A
  battery pinned at a charge cap never rises, so demanding a rise as proof leaves the verdict stuck
  at "not charging" for as long as the phone stays on the charger. An earlier draft did exactly
  that and was caught on-device: `last_level=80, last_inference=false`.
- **A plateau must keep the previous verdict**, or it oscillates. A phone draining
  80 -> 79 -> 79 -> 78 would read as discharging on each drop and charging again on every plateau
  between drops, flapping the cadence the whole way down.

Cost, accepted: a genuinely unplugged phone above 78% reads as charging until it loses its first
percentage point, then reads correctly for the rest of the drain. Bounded and self-correcting.

### 2. The plug-in trigger no longer re-fires every 10 minutes

`PowerConnectedJobService` re-armed unconditionally with a 10-minute latency. On a device left
plugged in, the charging constraint was already satisfied when the re-arm landed, so it fired,
re-armed, and became a permanent 10-minute loop duplicating the charging loop. Seen on SM-F936U1 as
POWER_CONNECTED_EVENT rows at 14:45/14:55/15:05/15:15, `elapsedMs` ~600000.

`ensureScheduled` now also declines while charging: a plug-in trigger has nothing to wait for on a
device already on the charger. `REARM_LATENCY_MS` is gone.

Re-arming moved to `OpportunisticUpdateJobService`, the most reliable recurring execution on
battery. It cannot live on the trigger's own path: `PowerConnectedRefresh.run()` is called at the
top of `onStartJob`, so scheduling `JOB_ID` from there re-schedules the running job, which
JobScheduler treats as a replacement and answers with `onStopJob`.

## Verification

- `:app:testDebugUnitTest` + `:shared:test` green (1,955 app tests).
- `BatteryTrendInferenceTest` (11 tests) proven to fail when the plateau rule is replaced with the
  naive "not dropping means charging": 2 failures, including the drain-oscillation case.
- `PowerConnectedJobServiceTest` (9 tests) proven to fail with the charging guard disabled.
- On SM-F936U1: `D PowerConnectedJob: Already charging - plug-in trigger not needed` where the old
  build re-armed; `BatteryChargeTrend: trend prev=79 cur=79 prevInf=true inf=true` with
  `battery_charge_trend_prefs.xml` holding `last_level=79, last_inference=true`.

## Not done

- The 13:51-14:06 divergence is unexplained. The trend rule covers the *symptom* of a platform
  under-reporting charging, but the cause was not identified.
- Not observed end to end: a device held at a charge cap for hours with the trend latching and the
  10-minute loop running off it. That needs the phone on its own charger for a long stretch.
