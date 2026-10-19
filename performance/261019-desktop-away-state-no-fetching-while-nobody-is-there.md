# Desktop away state: no background fetching while nobody is there

**Status:** proposed — awaiting approval (2026-10-19).

## Problem

An always-on workstation (no battery, so always "charging") left from 18:00 to 08:00 keeps fetching
on the charging, screen-off rows of the cadence:

| What | Rule | Per night (14 h) | Per weekend (~62 h) |
|---|---|---|---|
| Current temp / observations (loop 3b) | every 16 min (`CurrentTempCadence`) | ~52 | ~230 |
| SHORT forecast, every source (loop 3c) | every 8 h | 1–2 | ~8 |
| LONG forecast, every source | every 24 h | 0–1 | ~3 |

Nobody sees any of it. Google bills its part.

The morning return already works. Unblanking wakes the forecast loop at once
(`FORECAST_LOOP_WAKE reason=screen`), and the screen-on intervals are always due after a night.

**A second bug makes it worse.** `ScreenStateDetector.isScreenOn` asks `xset` (monitor power) before
`loginctl LockedHint`. A session that is locked overnight with a monitor that never powers down reads
as screen *on* all night, and takes the faster screen-on rows (observations every 10 min, LONG every
8 h, SHORT every 4 h).

## Rule (`:shared` `UserPresence`, pure)

- **Present** = screen on **and** session unlocked.
- **Away** = not present continuously for ≥ `AWAY_AFTER_MS` (60 min). A lunch break with a blanked
  screen stays under it and changes nothing.
- `lastPresentMs` moves forward on: each loop pass that finds the user present, an unblank or unlock
  signal, and any popup or panel interaction.

## Changes

1. **Screen state:** a locked session counts as screen off, whatever the monitor says
   (`ScreenStateDetector`: LockedHint=yes → off, else `xset`, else fail-open as now). This affects every
   screen-dependent cadence on desktop, so it is a behaviour change of its own: locked = off.
2. **Away → no background fetching:**
   - **Loop 3b** (observations): skips while away, re-checking every 5 min
     (`SUSPEND_RECHECK_INTERVAL_MS`, as it does for low battery).
   - **Loop 3c** (forecast): plans nothing while away. It sleeps up to its 2 h cap or until woken.
   - **Loop 3d** (non-primary observations) is already screen-on only.
   - Logged on transition: `AWAY_STATE away=true since=…` / `away=false awayMin=… skippedObs=… skippedForecast=…`.
3. **Return:** on unblank or unlock after away, catch up as a resume does:
   - Observations are fetched now. `kickObservationCatchUp` currently skips on a charger
     (`CurrentTempCadence.screenOnCatchUp` → NONE, because "charging has its own 10-min loop"); after
     away it fetches.
   - The forecast loop is woken (already done). The displayed source's forecast follows the 8 h
     catch-up cap from `performance/261019-desktop-notebook-morning-forecast.md`.
   - An unlock with no ScreenSaver signal is heard through logind's session `Unlock` / LockedHint
     change, added to the existing gdbus monitors if the ScreenSaver signal doesn't cover it. Check
     which signal this machine's desktop actually sends.
4. **Night fallback when the screen state can't be read** (user, 2026-10-19). With neither `xset`
   nor `loginctl` answering (e.g. some Wayland sessions), `ScreenStateDetector` fails open to "on". Such
   a machine would never go away, and would fetch all night at screen-on rates. In that case only,
   00:00–05:00 local counts as away. Panel or popup use during it still counts as present and ends it
   at once. Where detection works, the hour of day plays no part: a 2 am user has the screen on and is
   served, and a deliberate overnight weather display keeps fetching. Logged:
   `AWAY_STATE away=true basis=night_fallback`; detected away logs `basis=screen`.
5. **User-asked fetches are unaffected:** Refresh, source switches, day taps and history refills never
   ask the away rule.

## What it costs

- **Accuracy tracking: unaffected.**
  - "Yesterday's forecast" (`PriorDayForecast`) uses the newest fetch before 06:00 / 16:00 the
    previous day, ≤ 48 h old, and the evening fetch before leaving serves it.
  - Overnight lows come from observation history pulled at the return catch-up. Check during
    implementation that the catch-up pulls the window the night needs, as the daily backfill probe
    does for phones switched off overnight.
- **The hourly view's forecast line for unattended hours** (night, weekend) comes from the last fetch
  before leaving instead of a fresher one. This is the only real loss.
- **The panel's current temperature** is old while nobody looks, and fresh within seconds of return.

## Tests

- `:shared` `UserPresenceTest`: away only after 60 min not present; any presence resets it; 59 min of
  blank is not away.
- `ScreenStateDetectorTest`: LockedHint=yes with monitor On → off; LockedHint=no → `xset` decides;
  neither available → on (fail-open), reported as *unknown* to the away rule.
- Night fallback: screen state unknown at 02:00 → away (`basis=night_fallback`), at 10:00 → present;
  a panel click at 02:00 → present; screen state known at 02:00 → the hour plays no part.
- Loop gates (pure decision functions): observation and forecast loops skip while away and resume on
  presence; user-asked refreshes ignore away.
- Simulation: a week of workstation days (present 08:00–18:00 weekdays) → background fetch counts
  before and after. Assert none between 19:00 and 08:00 or at weekends, and that each morning's first
  fetches land within seconds of the return.

## Verification

- Lock this machine for over an hour: `AWAY_STATE away=true`, no `REFRESH` / observation fetches in
  the logs until unlock. Then `away=false`, observations fetched, `FORECAST_LOOP_WAKE reason=screen`.

## As built (2026-10-19)

- `:shared` `UserPresence.away(screenOn: Boolean?, lastPresentMs, nowMs, zone)`; desktop
  `DesktopPresence` holds `lastPresentMs` and the away state, with a presence check every 60 s inside
  the fetch job. `markPresent` is called on unblank, a panel click (`.show`) and a resume. A return
  kicks `kickObservationCatchUp("presence:return")` and wakes the forecast loop (`reason=return`).
- `ScreenStateDetector.screenState()` (true / false / null) and the pure `combine(unlocked, monitorOn)`:
  a lock wins. `isScreenOn()` keeps failing open. `LockedHint` is read from `self`, else from the
  user's display session (`loginctl show-user … -p Display`). From a shell outside the session `self`
  fails on this machine; the autostarted daemon has `XDG_SESSION_ID`.
- **Loops while away:**
  - Loop 3b skips each would-be observation fetch (counted).
  - Loop 3c logs `FORECAST_LOOP_SLEEP … reason=AWAY due=…`, counts the due sources, and waits up to
    2 h or until woken.
  - A `network:*` launch refresh does nothing (`launchRefreshSkippedWhileAway`). A resume, startup or
    user request is unaffected.
- **Overnight observations come back on return.** The observation catch-up itself pulls 90 min, but
  the forecast refresh that runs on return (always due after a night) pulls the full 7-day observation
  series (e.g. `REFRESH source=NWS … obs=1725` at 08:01 on 2026-10-19). Still to confirm against a real
  away night: the night's low in `daily_history`.
- **Tests:**
  - `UserPresenceTest`, `ScreenStateDetectorTest.combine`, `DesktopPresenceTest`.
  - The workstation-week simulation: 317 observation fetches, none between 19:00 and 08:00 after the
    first night or at weekends, and a return at 08:00 each weekday.
