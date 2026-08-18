# Current temps stale on Samsung: the 45-minute battery tier, and a wrong turn

## Problem

Reported: "Auto update on samsung seems to have broken. 10 minute refresh of current observations
doesn't seem to be working. Last 3 times I checked it has been stale."

### There is no single "10 minute refresh"

`CurrentTempFetchPolicy` has exactly two cadences, and battery **level** is an on/off gate rather
than a rate:

| Condition | Interval | Constant |
|---|---|---|
| Charging, screen on | 10 min | `CHARGING_INTERVAL_MINUTES` |
| Charging, screen off | 16 min | `CHARGING_SCREEN_OFF_INTERVAL_MINUTES` |
| On battery, >65% | 45 min | `OPPORTUNISTIC_INTERVAL_MINUTES` |
| On battery, <=65% | none beyond the 60-480 min data fetch | `OPPORTUNISTIC_MIN_BATTERY_PERCENT` |

At 80% unplugged the answer is 45 minutes, and it does **not** get faster at 90% or 100%. Confirmed
empirically in the day's logs: opportunistic runs at 12:05:53 -> 12:52:40 -> 13:35:55, i.e. 47 and
43 minutes apart. Add NWS observation lag (`observedAt` typically trails 5-20 min) and "stale by
more than an hour" falls straight out of that tier.

Three separate gates all collapse to `isCharging` — `shouldScheduleChargingLoop`,
`postRunLoopAction` (returns `NO_RESCHEDULE` on battery), and `shouldFetchNow` (non-opportunistic
returns bare `isCharging`). The loop is a self-perpetuating chain, so on battery it does not
degrade; it **stops**, and only a plug-in or another scheduler restarts it.

### A wrong turn, recorded deliberately

The first diagnosis was that Samsung's Protect Battery mode (`pbm=max`) reports
`plug=none status=discharging` while holding the charge cap, defeating every branch of
`BatteryStatePolicy.isEffectivelyCharging` — `FULL_BATTERY_LEVEL` being hardcoded to 100 and so
useless on a device capped at 80. The evidence cited was a `dumpsys batterystats` sample at
15:15:27 showing exactly that, while the phone was said to be on a charger.

**That was a misread, and it was stated with far more confidence than the evidence supported.**

Samsung's own `ACTION_BATTERY_CHANGED` history shows what actually happened:

```
14:34:08 -> 15:15:27   ac=true  online=38   (dedicated charger)
15:15:27              ac=false online=1     (3-second gap, cable swap)
15:15:30 ->           usb=true online=4     (laptop USB)
```

`ac=true` sets `EXTRA_PLUGGED = BATTERY_PLUGGED_AC`, so `plugged > 0` and `isEffectivelyCharging`
returned **true** for the whole time the phone sat on the charger. The 10-minute loop duly ran at
14:45, 14:55, 15:05 and 15:15, each logged `isPlugged=true`. The "smoking gun" sample was the three
seconds while the cable was being moved between chargers.

Two traps compounded it, both worth remembering:

- `dumpsys batterystats --history` **resets when the device is plugged in** (`RESET:TIME:` as its
  first line). Connecting the phone to read the history destroyed the window under investigation.
  The `ACTION_BATTERY_CHANGED` history inside `dumpsys battery` survives and should be used instead.
- Attaching the phone to USB sets `plugged > 0`, which makes the app charging by construction. Any
  "is the loop running?" check performed while tethered is measuring a charging device.

### What is actually still unexplained

Between **13:51:59 and 14:06:29** the JobScheduler charging constraint fired
(`POWER_CONNECTED_EVENT` at 13:51:59) while the app's own sticky-broadcast read reported
`plugged=false` (13:56:03, 14:06:29), and the battery level rose **76 -> 78** across the window. A
rising level is not a discharging device. The framework and the sticky broadcast disagreed; the
cause was not established.

## What changed

### 1. Charging inferred from a non-falling high battery level

Requested rule: infer charging from a battery level that is not dropping at 78% or above.

- `BatteryTier.HELD_CHARGE_MIN_LEVEL = 78` and `BatteryTier.inferChargingFromLevelTrend(...)`, pure
  and in `:shared`.
- New `BatteryChargeTrend` persists the two values that decision needs across process death, and
  writes only when something changed — `BatterySnapshotProvider.snapshot` is called from ~10 sites,
  several on widget-interaction paths, so an unconditional commit would be a write per tap.
- `BatterySnapshotProvider` ORs the inference with the platform answer, making the platform result
  a floor rather than the whole truth.

Rules: below 78 never infer; a **rise** means charging; a **drop** means discharging; a **plateau**
keeps the previous verdict, and counts as charging before any verdict exists.

Both plateau rules are load-bearing, and the first was learned the hard way:

- **A plateau must count as charging**, or the rule cannot latch in the very case it exists for. A
  battery pinned at a charge cap never rises, so demanding a rise as proof leaves the verdict stuck
  at "not charging" for as long as the phone stays on the charger. The first draft did exactly that
  and was caught on-device: `last_level=80, last_inference=false`.
- **A plateau must keep the previous verdict**, or it oscillates. A phone draining
  80 -> 79 -> 79 -> 78 would read as discharging on each drop and charging again on every plateau
  between drops, flapping the fetch cadence the whole way down.

Cost, accepted: a genuinely unplugged phone above 78% reads as charging until it loses its first
percentage point, then reads correctly for the rest of the drain. Bounded and self-correcting.

### 2. The plug-in trigger no longer re-fires every 10 minutes

`PowerConnectedJobService` (shipped earlier the same day) re-armed unconditionally with a 10-minute
latency. On a device left plugged in, the charging constraint was still satisfied when the re-arm
expired, so it fired, re-armed, and became a permanent 10-minute loop duplicating the charging loop
it was meant to complement. Seen on SM-F936U1 as `POWER_CONNECTED_EVENT` rows at
14:45 / 14:55 / 15:05 / 15:15 with `elapsedMs` ~600000.

`ensureScheduled` now also declines while charging — a plug-in trigger has nothing to wait for on a
device already on the charger. `REARM_LATENCY_MS` is gone.

Re-arming moved to `OpportunisticUpdateJobService`, the most reliable recurring execution the app
gets on battery. It deliberately cannot live on the trigger's own path: `PowerConnectedRefresh.run()`
is called at the top of `onStartJob`, so scheduling `JOB_ID` from there re-schedules the *running*
job, which JobScheduler treats as a replacement and answers with `onStopJob` — the defect fixed
earlier that day by moving the re-arm after `jobFinished`. The new charging guard does not reliably
prevent it either, since JobScheduler's charging notion and the sticky broadcast demonstrably
disagree on this device.

## Verification

- `:app:testDebugUnitTest` + `:shared:test` green (1,955 app tests).
- `BatteryTrendInferenceTest` (11 tests) **proven able to fail**: replacing the plateau rule with
  the naive "not dropping means charging" produced 2 failures, including the drain-oscillation case.
- `PowerConnectedJobServiceTest` (9 tests) **proven able to fail**: disabling the charging guard
  produced 2 failures.
- `OpportunisticUpdateJobServiceTest` fixture updated — its mocked `Context` needed real
  preferences behind it, with a distinct store per fixture so the trend's deliberate stickiness
  cannot leak a verdict between tests.
- On SM-F936U1: `D PowerConnectedJob: Already charging - plug-in trigger not needed`, exactly where
  the previous build re-armed; `BatteryChargeTrend: trend prev=79 cur=79 prevInf=true inf=true`
  with `battery_charge_trend_prefs.xml` holding `last_level=79, last_inference=true`.
- The per-read trend trace is `Log.v`, per the project convention for high-frequency logs: never
  persisted to `app_logs`, needs `setprop log.tag.BatteryChargeTrend VERBOSE` to reach logcat.

## Follow-ups

- **The reported staleness is most likely the 45-minute on-battery tier working as designed**, not
  a regression. The trend rule is a reasonable safety net for platforms that under-report charging,
  but it has not been shown to address a fault on this device.
- The 13:51-14:06 divergence between JobScheduler's charging constraint and the sticky broadcast is
  unexplained. The trend rule covers the *symptom*; the cause is still open.
- Not observed end to end: a device held at a charge cap for hours with the trend latching and the
  10-minute loop running off it. That needs the phone on its own charger for a long stretch, which
  also means no USB tether — see the tethering confound above.
- If the 45-minute on-battery tier is itself too slow, that is a separate and simpler change
  (`OPPORTUNISTIC_INTERVAL_MINUTES`), with a straightforward battery cost.

## Operational note

adb-over-Wi-Fi was enabled on the Samsung mid-investigation (`adb tcpip 5555`) to keep a connection
while the phone sat on its own charger, without flagging it first. It has since been closed
(`adb disconnect` + `adb usb`) and the device is back to USB-only.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/BatteryTier.kt` — threshold + pure rule
- `shared/src/test/kotlin/com/weatherwidget/shared/util/BatteryTrendInferenceTest.kt` — new
- `app/src/main/java/com/weatherwidget/widget/BatteryChargeTrend.kt` — new, persisted trend state
- `app/src/main/java/com/weatherwidget/widget/BatterySnapshotProvider.kt` — ORs the inference in
- `app/src/main/java/com/weatherwidget/widget/PowerConnectedJobService.kt` — re-arm fix
- `app/src/main/java/com/weatherwidget/widget/OpportunisticUpdateJobService.kt` — re-arm seam
- `app/src/test/java/com/weatherwidget/widget/PowerConnectedJobServiceTest.kt`
- `app/src/test/java/com/weatherwidget/widget/OpportunisticUpdateJobServiceTest.kt`
- `plans/260818-current-temp-refresh-stale-on-samsung.md`
