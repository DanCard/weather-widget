# GPS resample on charger plug-in and unlock

**Date:** 2026-08-07
**Commit:** `ee0816ad`
**Files:** `GpsResampler.kt`, `ScreenOnReceiver.kt`, `AppModule.kt` (app),
`GpsResamplerTest.kt`, `ScreenOnReceiverTest.kt`

## Problem

Reported on the **Samsung**: daily view, today column, thermostat low read **69.8°** when it should
have been **60.8°**. The Pixel — same build, same location, but it had not moved — was correct.

Real data corruption, not a rendering bug. `TODAY_BAR_DEBUG` brackets it exactly:

```
13:20:59  obsHigh=71.23  obsLow=60.7995   ← correct
13:32:11  obsHigh=70.11  obsLow=69.8242   ← wrong
```

The device had moved that morning (Google HQ → Sunnyvale → Bayshore Freeway) and GPS promoted a new
site at 13:23:01. At 13:26:57 an opportunistic **current-temp** fetch ran there and created site
`37.402,-122.041` holding **5 observation rows** (12:10–13:20). `getObservationsInRange` collapses
the ±0.1° box to the *nearest* site, so every read switched to that stub and discarded the 455 rows
already collected for the day at sites 3–5 km away:

| site | rows today | first obs | min |
|---|---|---|---|
| `37.417,-122.089` (Google HQ) | 157 | 00:00 | 60.0 |
| `37.406,-122.021` (Sunnyvale) | 298 | 00:00 | 60.0 |
| **`37.402,-122.041` (new)** | **5** | **12:10** | 68.0 |

"Today's low" stopped meaning the overnight minimum and became the coldest of the last hour.
`EXTREMA_WINDOW_DIAG` shows the blend starving: `isolated=[hi=69.80@13:15 lo=69.80@13:15 pts=1]`.

**Why it persisted for 41 minutes** — the part this commit addresses. The GPS auto-heal runs only
from `WeatherWidgetWorker.handleFullSyncWork`, behind a gate excluding `uiOnlyRefresh`,
`currentTempOnly`, `nonPrimaryCurrentTempOnly`, `observationBackfillMode` and
`candidateLocationRefresh`. `ScreenOnReceiver`'s plug-in and unlock handlers enqueue *exactly* those
excluded kinds, so neither event ever resampled. The device's own fused provider read Google HQ
throughout (fix ~9 min old, matching the Pixel); only the app's saved location disagreed, and it
stayed wrong until the next full sync at 14:04:19.

## What changed

### 1. `resample` takes a trigger

`GpsResampler.resample(context, trigger = "worker")`. Every breadcrumb outcome now carries
`trigger=`, including `skipped_no_permission` and `no_fix`, which previously did not.

The name is not just a log label: `healIfNeeded` passes `enqueueRefresh = trigger != "worker"`. The
worker is already mid-sync and fetches the candidate itself; an event-driven caller is not, so it
must enqueue a refresh or the candidate never accumulates the data `evaluateCandidateUsability`
requires for promotion. **A future trigger named `"worker"` would silently never heal.**

### 2. `ScreenOnReceiver` resamples on plug-in and unlock

New `resampleLocationAsync(context, trigger)` called from `handlePowerConnected`
(`trigger="power_connected"`) and `handleUserPresent` (`trigger="user_present"`).

A resample is a passive `lastLocation` read — no GPS power-up, no network, no Samsung
precise-location notice — so it is cheap enough for these events. Debounced 2 min by reusing
`PowerConnectedRefreshPolicy` because unlock fires dozens of times a day and a *detected* candidate
costs a reverse-geocode. The debounce is deliberately **independent** of the current-temp one: a
plug-in too soon for another fetch is still the moment the device most likely finished moving.

### 3. Hilt access without `@AndroidEntryPoint`

`ScreenOnReceiver` stays a plain `BroadcastReceiver` — `ScreenOnReceiverTest` constructs it directly
under a plain Robolectric application, and Hilt's generated `onReceive` would throw there. It
resolves `GpsResampler` through `RepositoryEntryPoint` (`EntryPointAccessors`) at call time, behind
the `resampleLocation` test seam. `RepositoryEntryPoint` gained a `gpsResampler()` method.

## Verification

Unit tests only — 6 new, all green alongside the existing suites:

- `ScreenOnReceiverTest`: plug-in resamples, unlock resamples, repeated events are debounced, and the
  resample still runs when the current-temp refresh is debounced (pinning the independence claim).
- `GpsResamplerTest`: an event trigger labels the breadcrumb *and* sets `enqueueRefresh=true`; the
  worker trigger sets it `false`. Two existing breadcrumb assertions updated for the new `trigger=`
  token, and `proposeCandidate`'s stub now captures the flag.

Mutation-checked: commenting out the `power_connected` call site fails 3 of them.

**Not demonstrated on-device.** `dumpsys battery unplug` / `set ac 1` did not deliver
`ACTION_POWER_CONNECTED` to the app, and a scripted lock/swipe produced no `ACTION_USER_PRESENT`.
Neither produced a `POWER_CONNECTED_EVENT` nor an `UNLOCK_REFRESH_POLICY` row — and the latter is
*pre-existing* code, so what failed was broadcast delivery from the simulation, not the new code.
Real proof needs an actual cable pull or lock/unlock; `SELECT * FROM app_logs WHERE
tag='GPS_RESAMPLE'` should then show `trigger=power_connected`.

Note the reported symptom cleared on its own at 14:04:19, when a full sync resampled and found Google
HQ again (`daily_history` NWS back to `hi=70.1 lo=60.8`; `TODAY_BAR_DEBUG obsLow=60.81744`). **This
commit is not what fixed it** — it removes the 41-minute wait before the same recovery happens.

## Follow-ups

Two defects found during diagnosis, both still live:

1. **Cross-site write clobber.** `DailyActualsStore.persistExtremes` reads observations
   site-collapsed but reads `existingHistory` from `getExtremesInRange(...)`, which applies only
   `LocationMatch.ROOM_WHERE` — the coarse ~7 mi box, no `selectNearestSite`. It then writes the new
   values onto *every* fragment in the box via `new.copy(locationLat = existing.locationLat, …)`, so
   a recompute anchored at site A overwrites site B's row. Here the stub's 69.8 destroyed the old
   site's correct 60.8, which is why the damage outlived the move. Same shape as
   `260730`'s cross-site repair bug: an uncollapsed pool filtered by `source` but not `sameSite`.
   Fix: constrain `persistExtremes` to `sameSite` fragments.
2. **Handoff readiness is observation-blind.** `LocationHandoffPolicy.evaluateCandidateUsability`
   gates promotion on daily + hourly **forecast** coverage only. A site with zero observations is
   declared `complete_visible_coverage` and promoted — and observations are the entire substance of
   the today-column thermostat. That branch also returns *before* the `MOVING_GRACE_MS` (30 min)
   check, so the guard that exists to stop a drive promoting intermediate sites never engaged.

Minor: the sparse-history self-heal (`OBS_HOURLY_BACKFILL`,
`reason=temperature_graph_sparse_history`) would repopulate a new site, but its cooldown key is
`"${displaySource.id}_HOURLY_HISTORY"` per widget with **no site component**, so a heal at the old
site suppressed the new site's for 30 min.
