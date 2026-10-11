# One current-temperature / observation cadence for Android and desktop, in `:shared`

User, 2026-10-11: "for Current temperature / observations (network) unify desktop and android, shared
code". Android's values win on all three differences (user's answers, same day).

## Today

| | Android (`CurrentTempFetchPolicy`, app) | Desktop (`DesktopFetchStrategy.getObservationRefreshDelayMs`) |
|---|---|---|
| Charging, screen on | 10 min | 10 min |
| Charging, screen off | **16 min** | **30 min** |
| Battery ≥ 70 %, screen on | 20 min | 20 min |
| Battery otherwise | **45-min opportunistic job, > 65 %; else none** | **> 70 %: 240 min · > 50 %: 480 min · else none** |
| Screen-on catch-up | **fetch if last current-temp fetch ≥ 20 min old, battery ≥ 70 %, not charging** | **fetch if last forecast `REFRESH` ≥ 10 min old, any battery** |
| Other sources' observations | 30 min charging + screen on (`:shared` `NonPrimaryObservationPolicy`) | same |

The numbers live in two files; only the 20-min rule and the non-primary rule are shared. Desktop's
battery tiers are the forecast tiers (240 / 480) copied onto observations. Desktop's catch-up reads
`getLastSuccessfulFetch` — the **forecast** `REFRESH` row — so it judges observation freshness by the
forecast's age.

## New rule (both platforms)

| Power | Screen on | Screen off |
|---|---|---|
| Charging (plugged in) | 10 min | 16 min |
| Battery ≥ 70 % | 20 min | 45 min (> 65 %) |
| Battery 66–69 % | 45 min | none |
| Battery ≤ 65 % | none | none |

Screen-on catch-up: fetch at once when not charging, battery ≥ 70 %, and the last **current-temperature**
fetch is ≥ 20 min old (or none); otherwise the loop's own schedule stands. Charging needs no catch-up
(10-min loop). Other sources' observations: unchanged (30 min, charging + screen on).

"Charging" here is physically plugged in (`isCharging`), not the forecast's ≥ 80 % stand-in — as
today on both platforms.

## Code

- **`:shared` `CurrentTempCadence`** (new; `shared/util`), the one definition:
  - `intervalMinutes(isCharging, screenOn, batteryLevel): Long?` — the table above (charging 10/16;
    battery screen-on ≥ 70 % → 20; > 65 % → 45; else null).
  - `screenOnCatchUp(isCharging, batteryLevel, lastFetchMs, nowMs)` → `NONE | FETCH_NOW | SCHEDULE`
    and `screenOnFirstDelayMinutes` (moved from Android `CurrentTempFetchPolicy`).
  - Constants `CHARGING_SCREEN_ON_MINUTES = 10`, `CHARGING_SCREEN_OFF_MINUTES = 16`,
    `OPPORTUNISTIC_MINUTES = 45`, reusing `BatteryTier.SCREEN_ON_OBSERVATION_*` and
    `OPPORTUNISTIC_MIN_BATTERY_PERCENT`.
  - `opportunisticAllowed(batteryLevel, screenOn)`: the 45-min band — screen on > 65 %, screen off
    ≥ 70 % (user, 2026-10-11: "Battery 66–69 %, screen off, change that to none").
- **Android**: `CurrentTempFetchPolicy` keeps its mechanism-specific parts (which Android scheduler
  runs which band: WorkManager charging loop, the battery screen-on RTC alarm, the 45-min JobScheduler
  job; `shouldFetchNow`, `shouldScheduleOpportunisticJob`, `opportunisticTargetSourceId`, repaint
  skip) and takes its numbers and the catch-up from `CurrentTempCadence`. **One Android change:** the
  screen-blind 45-min JobScheduler job now skips its run when the screen is off and the battery is
  66–69 % (`shouldFetchNow` asks `opportunisticAllowed`); scheduling the job is unchanged (> 65 %).
- **Desktop**: `DesktopFetchStrategy.getObservationRefreshDelayMs` → `CurrentTempCadence.intervalMinutes`
  (the 3b loop's `delay`). Desktop has one loop, so the 45-min band is just its interval when the
  screen is off or the battery is under 70 %. `shouldCatchUpObservations` and its 10-min threshold are
  removed; `kickObservationCatchUp` asks `CurrentTempCadence.screenOnCatchUp` with the last
  **observation** fetch (`getLastSuccessfulObservationFetch`), not the forecast's `REFRESH` row.
  `AC_OBSERVATION_SCREEN_*_MINUTES` and `CATCH_UP_STALENESS_THRESHOLD_MINUTES` removed.
- CLAUDE.md: the "Recent observations" row of the Update Strategy table.

## Behaviour change (desktop only)

- Charging, screen off: 30 → 16 min.
- Battery, screen off or 66–69 %: 4–8 h → 45 min.
- Battery 51–65 %: 8 h → none (screen on or off).
- Battery 66–69 % with the screen off: none (Android too: its 45-min job skips that run).
- Screen wake: catch-up only on battery ≥ 70 % with a ≥ 20-min-old observation (was any battery,
  ≥ 10 min, measured against the forecast); charging relies on its 10-min loop.

## Tests

- `CurrentTempCadenceTest` (shared): every cell of the table and its boundaries (65/66, 69/70 %;
  charging screen on/off); catch-up NONE / FETCH_NOW / SCHEDULE incl. never fetched; first-delay floor.
- Android: existing `CurrentTempFetchPolicy*` tests keep passing unchanged (delegation, same numbers).
- Desktop: `DesktopFetchStrategyTest` updated to the shared values; catch-up tests rewritten against
  `screenOnCatchUp`.
- Integration (desktop, real SQLite): an observation fetch's row is what the screen-wake catch-up reads
  — a fresh observation with an old forecast `REFRESH` row does not catch up; a stale one does.

## As built (2026-10-11)

- `:shared` `CurrentTempCadence`: `intervalMinutes` (desktop's one loop), `loopIntervalMinutes` +
  `opportunisticAllowed` (Android's loop / 45-min job), `screenOnCatchUp`, `screenOnFirstDelayMinutes`.
  Android `CurrentTempFetchPolicy` names its constants from it and delegates; `ScreenOnCatchUp` moved
  to `:shared`.
- Desktop catch-up kicks: **screen wake** (`screensaver:*`) uses `CurrentTempCadence.screenOnCatchUp`;
  a **genmon panel click** is user interaction, not a schedule, so it keeps the 10-min staleness check
  (`DesktopFetchStrategy.shouldCatchUpObservations`) — the shared screen-on rule would have refused
  every click while charging. Both now read `getLastSuccessfulObservationFetch` (`OBS_REFRESH` /
  `REFRESH`), not the forecast's `REFRESH` age. Decision in `DesktopFetchStrategy.shouldCatchUpOnKick`.
- Tests: `CurrentTempCadenceTest` (shared), `DesktopFetchStrategyTest` (new values, wake vs click),
  `DesktopObservationCatchUpTest` (real SQLite: fresh `OBS_REFRESH` beside an old `REFRESH` does not
  catch up), Android `CurrentTempFetchPolicyTest` (66–69 % screen off now refused).
