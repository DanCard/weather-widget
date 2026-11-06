# Screen-on re-plans the charging observation loop

Date: 2026-11-06 · Platforms: desktop + Android (shared rule)

## Problem

On a charger with the screen on, station observations should refresh every 10 min
(`CurrentTempCadence.CHARGING_SCREEN_ON_MINUTES`). On 2026-11-06 the desktop Stations window still said
"Fetched 8:29 AM" at 08:42, while the user was at the machine.

## Evidence (desktop, 2026-11-06)

1. Daemon log: `Temp actuals loop refresh starting` at 08:19:20 and 08:29:21 (10 min apart), then
   nothing. The fetch due at ~08:39:23 never started, although the daemon was alive (the 08:40:00
   `CURRENT_STATUS_PUBLISH` tick ran).
2. Not away: the last `AWAY_STATE` transition is from 2026-11-03.
3. DPMS here is `Standby: 540 Off: 600`. `ScreenStateDetector.parseXsetMonitorState` reads Standby as
   screen off, and screen off on a charger means a 60-min wait.
4. No screen-on signal arrives: since the daemon started (2026-11-05 16:58) there is no
   `kickObservationCatchUp (screensaver:…)` line in the log, even after the night. The wake listener
   only watches `org.freedesktop.ScreenSaver ActiveChanged`, which DPMS standby → on does not emit.
5. There was no record of the wait each pass chose. The `OBS_LOOP_PLAN` app_logs line added with this
   plan now records it (`screenOn=… charging=… level=… watchArmed=… delayMin=…`). First line after the
   restart: `screenOn=true charging=true level=100 watchArmed=false delayMin=10`.

## Root cause

1. **The desktop observation loop sleeps a fixed interval chosen once per pass**
   (`DaemonRuntime.kt` §3b, `delay(delayMs)`). If the screen reads off at the moment of choosing, the
   wait is 60 min, and nothing shortens it. The forecast loop already has a wake channel
   (`forecastLoopWake`); the observation loop has none.
2. **The shared screen-on rule leaves a recent fetch alone on a charger.**
   `CurrentTempCadence.screenOnCatchUp` returns `NONE` when charging and the last fetch is under 15 min
   old ("leave the loop be"). But the loop was scheduled 60 min out while the screen was off, so it
   keeps that pace. Android's `ScreenOnReceiver.startBatteryScreenOnLoop` has the same gap: a screen-on
   1–14 min after a screen-off fetch keeps the 60-min `WorkManager` delay.
3. **Desktop misses DPMS-only wakes.** Only the ScreenSaver D-Bus signal counts as a wake, so a
   monitor coming out of standby without a lock never triggers one.

## Proposed fix

### Shared (`:shared`, `CurrentTempCadence`)

- On a charger, `screenOnCatchUp` returns `FETCH_NOW` when the last fetch is ≥ 10 min old
  (the charging screen-on interval). Otherwise it returns `SCHEDULE` with the first run due
  `10 − age` minutes after the last fetch, never `NONE`. On battery nothing changes.
- Generalize `screenOnFirstDelayMinutes(lastFetchMs, nowMs)` to take the interval (charging 10, battery
  20), so both branches share one helper.
- Remove `CHARGING_SCREEN_ON_CATCH_UP_MINUTES` (15). The catch-up threshold becomes the loop interval
  itself, as on battery.

### Desktop

1. Give the observation loop a conflated wake channel (`observationLoopWake`, like `forecastLoopWake`).
   Each pass waits for `select { delay(remaining); wake }` rather than a fixed `delay`.
2. On every wake, re-plan from the **last observation fetch time**
   (`getLastSuccessfulObservationFetch`) through the shared `screenOnCatchUp`/interval rule. If it says
   `FETCH_NOW`, fetch. Otherwise sleep the remaining time. No fetch happens sooner than the shared
   interval allows.
3. Wake sources:
   - the existing ScreenSaver `ActiveChanged false` line;
   - **new:** a screen off→on edge seen by the presence tick (`DesktopPresence.evaluate`, every 60 s,
     already reading `xset`/`loginctl`). This catches DPMS standby/off → on within a minute, with no new
     process;
   - power-tier changes (already sent to the forecast loop; also send them here).
4. `kickObservationCatchUp("screensaver:…")` becomes a wake of the loop rather than a separate fetch
   path, so there is one owner of the observation timing. Panel clicks (`genmon:click`) keep the
   existing 10-min user catch-up.
5. Keep the `OBS_LOOP_PLAN` line, and add `wake=<reason>` when a wake re-plans the wait.

### Android

- `ScreenOnReceiver.startBatteryScreenOnLoop` already turns `SCHEDULE` into
  `scheduleNextChargingUpdate(firstDelayMinutes = …)`, so the shared change gives it the right
  behavior on a charger. Check that the delayed charging request uses `REPLACE` only for delayed work
  (allowed by AGENTS.md rule 6) and never cancels a running `WeatherWidgetWorker`.

## Tests (category rules in `arch/testing-strategy.md`)

1. `CurrentTempCadenceTest` (JVM): charging + screen-on at ages 0, 7, 9, 10, 14, 15, 60 min →
   `SCHEDULE` with first delays 10, 3, 1 and then `FETCH_NOW`. Battery cases unchanged. The 7-min case
   fails on the old code (`NONE`).
2. Desktop JVM test of the loop's planning step, pulled out as a pure function
   (`ObservationLoopPlan.next(lastFetchMs, now, power, screenOn, watchArmed)`). Screen off at pass time
   → 60 min. A screen-on wake 7 min later → 3 min remaining.
3. Desktop test: a `DesktopPresence` screen edge (false → true, fake `screenState`) sends exactly one
   wake. True → true sends none.
4. Android Robolectric: a `ScreenOnReceiver` screen-on on a charger with a 7-min-old fetch schedules a
   charging update with a 3-min first delay (previously nothing).

## Runtime verification

1. Desktop: let the monitor go to standby (≥ 9 min idle) right after a fetch, then move the mouse.
   Expect an `OBS_LOOP_PLAN … delayMin=60` line, then `wake=screen_edge` within ~60 s, then
   `OBS_REFRESH` no later than 10 min after the previous fetch. The Stations window's "Fetched" time
   advances.
2. Android emulator on AC: screen off right after a fetch, screen on 5 min later. Expect the next
   current-temp run ~5 min later, not ~55.

## Docs

- `arch/hourly-forecast-update-cadence.md` "Current observations and temperature": replace the
  "charging ≥ 15 minutes" catch-up text with the re-plan rule, and add the DPMS edge as a desktop wake
  source.
- `CurrentTempCadence` KDoc table and `screenOnCatchUp` comment.

## Out of scope

- Changing the cadence table itself (10/60/30 min).
- Listening to DPMS through a new D-Bus/X11 event source. The 60-s presence tick is enough.
