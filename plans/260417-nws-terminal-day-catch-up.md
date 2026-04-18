# NWS Terminal Day Catch-Up Scheduler

## Problem

NWS provides a ~7-day forecast. For the last day in its range (the "terminal day"), NWS initially
returns only a nighttime period with a low temperature and no high. The daytime high for this
terminal day typically appears between **6:00 PM - 7:30 PM local time** during the NWS midnight-UTC
update cycle. Until then, the widget shows only a low temperature for that day, with no climate
normal fallback (intentionally blocked by `isTerminalLowOnlyNwsFutureDay()`).

## Solution

Add a targeted NWS-only refresh scheduler that fetches every ~15 minutes between **6:15 PM and
7:30 PM local time**, only when:

- Device is **charging** (plugged in)
- Screen is **on** (interactive)
- The terminal NWS day **still has a missing high temperature**

Uses randomized jitter (±3 min per attempt, 0-5 min initial delay) to avoid thundering herd on the
NWS API.

## Architecture

### New Files (2) — `widget/` package

#### 1. `NwsTerminalDayCatchUpPolicy.kt`
Pure decision functions, no Android dependencies, fully testable.

| Function | Purpose |
|----------|---------|
| `isInCatchUpWindow(now: LocalTime): Boolean` | `true` if `now` ∈ [18:15, 19:30] |
| `shouldScheduleCatchUp(isCharging, isScreenOn, isInWindow): Boolean` | All three must be `true` |
| `detectTerminalDayMissingHigh(nwsForecasts, today): TerminalDayInfo?` | Finds the furthest-future NWS day; returns it only if `highTemp == null && lowTemp != null` |
| `computeJitteredDelay(baseMinutes: Long, random: Random): Long` | Returns `(baseMinutes ± random(0,3)) * 60_000` ms |
| `computeInitialDelay(now: LocalTime, random: Random): Long` | Returns ms until 18:15 + random(0,5 min), or 0 if already in window |

Constants:
- `WINDOW_START = LocalTime.of(18, 15)`
- `WINDOW_END = LocalTime.of(19, 30)`
- `BASE_INTERVAL_MINUTES = 15L`
- `JITTER_MINUTES = 3L`

#### 2. `NwsTerminalDayCatchUpScheduler.kt`
Follows the `HourlyObservationBackfill` + `CurrentTempUpdateScheduler` patterns.

| Function | Purpose |
|----------|---------|
| `evaluateCatchUpNeed(forecasts, today): CatchUpDecision` | Pure: calls `detectTerminalDayMissingHigh()` |
| `maybeEnqueueCatchUp(context, database, stateManager, widgetId, lat, lon)` | Evaluate → cooldown (15 min) via `WidgetStateManager.shouldRefreshMissingData()` → enqueue one-time work |
| `scheduleNextCatchUpAttempt(context)` | Self-perpetuating: enqueues delayed work (15 min + jitter) |
| `cancel(context)` | Cancels unique work by name |

Constants:
- `COOLDOWN_MS = 15 * 60 * 1000L`
- `SOURCE_KEY = "NWS_TERMINAL_DAY"` (for WidgetStateManager cooldown key)
- `WORK_NAME = "weather_widget_nws_terminal_catch_up"`

### Modified Files (3)

#### 3. `WeatherWidgetWorker.kt`
- Add constant: `KEY_NWS_TERMINAL_CATCH_UP = "nws_terminal_catch_up"`
- Read in `doWork()`: `val nwsTerminalCatchUp = inputData.getBoolean(KEY_NWS_TERMINAL_CATCH_UP, false)`
- Exempt `nwsTerminalCatchUp` from cooldown gate (line 74)
- Add dispatch branch before full sync path:
  ```
  if (nwsTerminalCatchUp) return handleNwsTerminalCatchUpWork(...)
  ```
- New private method `handleNwsTerminalCatchUpWork()`:
  1. Calls `repository.getWeatherData(lat, lon, name, forceRefresh=true, targetSourceId="NWS")`
  2. Queries DAO for updated terminal NWS day → check if high is now present
  3. If high still missing AND still in window AND charging + screen on → calls `NwsTerminalDayCatchUpScheduler.scheduleNextCatchUpAttempt(context)`
  4. Calls `updateAllWidgets()` to repaint
- After full sync completes successfully (in `onSuccess` block), if in catch-up window, call `NwsTerminalDayCatchUpScheduler.maybeEnqueueCatchUp()` — this catches the case where the hourly periodic sync runs during the window

#### 4. `WeatherWidgetProvider.kt`
- Add `WORK_NAME_NWS_TERMINAL_CATCHUP = "weather_widget_nws_terminal_catch_up"` constant

#### 5. `ScreenOnReceiver.kt`
- In `handleUserPresent()`, after the existing charging loop logic:
  ```
  if (battery.isCharging && NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.now())) {
      // launch coroutine to check if terminal NWS day needs catch-up
      // if yes, enqueue immediate catch-up work
  }
  ```
- In `handleScreenOff()`, cancel the catch-up work alongside existing `CurrentTempUpdateScheduler.cancel()`

### Tests

| Test | Type | Location |
|------|------|----------|
| `NwsTerminalDayCatchUpPolicyTest` — window boundaries, gate logic, detection, jitter | Pure JVM (`ShortDuration`) | `test/` |

## Flow

```
Screen Unlock (during 6:15-7:30 PM, charging)
  └─ ScreenOnReceiver.handleUserPresent()
      └─ NwsTerminalDayCatchUpScheduler.maybeEnqueueCatchUp()
          ├─ evaluateCatchUpNeed() → queries DAO for NWS forecasts, finds terminal day missing high
          ├─ shouldRefreshMissingData() → 15-min cooldown gate
          └─ Enqueue OneTimeWork (NWS-only, immediate)
              └─ WeatherWidgetWorker.doWork() [nwsTerminalCatchUp branch]
                  ├─ getWeatherData(forceRefresh=true, targetSourceId="NWS")
                  ├─ High found? → log + update widgets → DONE
                  └─ High still missing + in window + charging + screen on?
                      └─ scheduleNextCatchUpAttempt() → 15 min + jitter delay
                          └─ (loops back to worker)

Hourly Periodic Sync (during 6:15-7:30 PM)
  └─ WeatherWidgetWorker.doWork() [full sync path, onSuccess]
      └─ NwsTerminalDayCatchUpScheduler.maybeEnqueueCatchUp()
          └─ (same flow as above)

Screen Off or Unplug
  └─ ScreenOnReceiver.handleScreenOff()
      └─ NwsTerminalDayCatchUpScheduler.cancel()
```

## Randomization

| Element | Base | Jitter |
|---------|------|--------|
| First attempt (if already in window) | immediate | none |
| First attempt (before window) | ms until 18:15 | +0 to +5 min |
| Subsequent attempts | 15 min | ±3 min |

## Design Decisions

1. **Stop immediately** on unplug or screen off. No tolerance for brief interruptions. Simpler and
   more battery-friendly. Re-enters on next screen unlock during the window.

2. **Full sync path also triggers** catch-up check, not just screen unlock. This ensures the catch-up
   runs even if the screen stays on continuously and only periodic syncs fire.

3. **NWS-only fetch** via `targetSourceId="NWS"` in `getWeatherData()`. Other sources are not
   disturbed.

4. **Cooldown via `WidgetStateManager.shouldRefreshMissingData()`** with `refreshType =
   "nws_terminal_catch_up"`. Reuses existing generic cooldown infrastructure; no new
   WidgetStateManager methods needed.

5. **No changes to `WidgetStateManager.kt`** — reuses `shouldRefreshMissingData()` /
   `markMissingDataRefreshRequested()` with custom `refreshType` string.

6. **No changes to `DailyViewLogic.kt`** — the terminal day detection logic is reimplemented in
   `NwsTerminalDayCatchUpPolicy` for the scheduler context (operates on raw forecast lists rather
   than `weatherByDate` maps).
