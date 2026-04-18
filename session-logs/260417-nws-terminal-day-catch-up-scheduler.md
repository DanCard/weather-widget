# Session Log: NWS Terminal Day Catch-Up Scheduler Implementation
**Date:** April 17, 2026
**Topic:** Design and implement a targeted NWS-only refresh scheduler that fetches every ~15 minutes between 6:15 PM and 7:30 PM local time when the terminal NWS forecast day has a missing high temperature.

## 1. User Prompts Used In This Session

1. `What did we do so far?`
2. `Continue if you have next steps, or stop and ask for clarification if you are unsure how to proceed.`
3. User answered three design questions:
   - Full sync too (recommended) — catch-up also triggered from full sync completion path
   - Stop immediately (recommended) — loop stops when unplugged or screen turns off
   - widget/ directory (recommended) — new files go in widget/ package
4. `Write fully specified plan to plans/ dir and implement.`

## 2. Context and Motivation

### Background (from earlier sessions today)

Earlier sessions investigated why the NWS "Friday next week" column on the emulator and Samsung device showed only a low temperature (no high), while the Pixel device showed both high and low correctly. The root cause was identified:

1. NWS provides a ~7-day forecast. For the last day in its range (the "terminal day"), NWS initially returns only a nighttime period with a low temperature — no high.
2. Later the same evening (typically 6:00-7:30 PM PDT), NWS updates its forecast and adds the daytime high for that terminal day.
3. The function `isTerminalLowOnlyNwsFutureDay()` in `DailyViewLogic.kt` intentionally blocks the climate normal fallback for terminal NWS days, so the widget displays only the low until the NWS API provides the high.
4. Whether a device has the updated data depends on whether it happened to fetch after the NWS update cycle — the Pixel fetched at 18:16 PDT (after the high appeared) while the emulator fetched at 17:57 PDT (before).

### Historical NWS High Appearance Timing

From ~4 weeks of Pixel fetch history, the 7th-day high consistently appeared between 6:02 PM - 7:22 PM PDT (average ~6:35 PM), corresponding to the NWS midnight-UTC forecast update cycle with a 1-2 hour propagation delay.

### Design Decision

Two options were presented:
- **Option 1**: Climate normal fallback for terminal days — rejected
- **Option 2**: Targeted NWS refresh scheduler during the 6:15-7:30 PM window — chosen

## 3. Planning Phase

### Architecture Research

Read and analyzed the following existing patterns before designing:

1. **`HourlyObservationBackfill.kt`** — Primary pattern for new work types:
   - Pure decision function (`evaluateHourlyBackfillNeed`) returning a structured decision
   - Enqueue function with cooldown gate via `WidgetStateManager.shouldRefreshMissingActuals()`
   - Unique work name with `ExistingWorkPolicy.KEEP`
   - Database logging for every skip/enqueue

2. **`CurrentTempUpdateScheduler.kt`** — Self-perpetuating work loop pattern:
   - `scheduleNextChargingUpdate()` with `setInitialDelay()` for delayed re-enqueue
   - `ExistingWorkPolicy.REPLACE` to replace completed work
   - `runCatching` wrappers for defensive error handling

3. **`WeatherWidgetWorker.kt`** — Worker dispatch:
   - Key reading at top of `doWork()`
   - Dispatch branches for observation backfill, current-temp-only, and full sync
   - Cooldown gate exemption pattern (line 74)
   - `KEY_TARGET_SOURCE` already passed to `getWeatherData()` for source-targeted fetches

4. **`ScreenOnReceiver.kt`** — Screen unlock handler:
   - Battery-aware branching
   - `goAsync()` + coroutine for database writes
   - Existing charging loop management

5. **`CurrentTempFetchPolicy.kt`** — Pure policy object pattern:
   - Simple gate functions
   - No Android dependencies

6. **`ForecastDao.kt`** — Available queries:
   - `getLatestForecastsInRangeBySource()` — key query for getting latest NWS batch per date
   - `getLatestWeather()` — simplest way to get location from cache

### Existing Infrastructure Leveraged

- **`ForecastRepository.getWeatherData(targetSourceId="NWS")`** already supports NWS-only fetches via `shouldForceSource()`
- **`WidgetStateManager.shouldRefreshMissingData()`** generic cooldown infrastructure — no new methods needed, just pass custom `refreshType` string
- **`WeatherWidgetWorker` already reads `KEY_TARGET_SOURCE`** from input data and passes it to `getWeatherData()`

### Design Questions Asked to User

Three questions were presented:

1. **Trigger point**: Full sync completion too, or only screen unlock? → User chose full sync too
2. **Loop interruption**: Stop immediately on unplug/screen off, or tolerate brief interruptions? → User chose stop immediately
3. **File location**: `widget/` or `widget/handlers/`? → User chose `widget/`

### Plan Written

Fully specified plan written to `plans/260417-nws-terminal-day-catch-up.md` containing:
- Problem statement
- Architecture overview (2 new files, 3 modified files)
- New file specifications with function tables
- Modified file specifications with integration points
- Flow diagram
- Randomization strategy table
- Design decisions rationale

## 4. Implementation Phase

### New File 1: `NwsTerminalDayCatchUpPolicy.kt`

Created `app/src/main/java/com/weatherwidget/widget/NwsTerminalDayCatchUpPolicy.kt`

Pure decision functions with no Android dependencies:

| Function | Implementation |
|----------|---------------|
| `isInCatchUpWindow(now)` | Checks `now ∈ [18:15, 19:30)` using `LocalTime` comparison |
| `shouldScheduleCatchUp(isCharging, isScreenInteractive, isInWindow)` | Returns `true` only when all three are `true` |
| `detectTerminalDayMissingHigh(forecasts, today)` | Filters NWS future forecasts, finds max date, returns `TerminalDayInfo` only if `highTemp == null && lowTemp != null` |
| `computeJitteredDelay(baseMinutes, random)` | `(baseMinutes ± 3) * 60_000` ms, coerced to at least 1 minute |
| `computeInitialDelay(now, random)` | Returns ms until 18:15 + random(0, 5 min), or 0 if already in window |

Data classes: `TerminalDayInfo(date, lowTemp)`, `CatchUpDecision(isNeeded, terminalDayInfo, reason)`

Constants: `WINDOW_START = 18:15`, `WINDOW_END = 19:30`, `BASE_INTERVAL_MINUTES = 15`, `JITTER_MINUTES = 3`

### New File 2: `NwsTerminalDayCatchUpScheduler.kt`

Created `app/src/main/java/com/weatherwidget/widget/NwsTerminalDayCatchUpScheduler.kt`

Singleton object following the `HourlyObservationBackfill` + `CurrentTempUpdateScheduler` patterns:

| Function | Implementation |
|----------|---------------|
| `evaluateCatchUpNeed(forecasts, today)` | Calls `detectTerminalDayMissingHigh()`, wraps in `CatchUpDecision` |
| `maybeEnqueueCatchUp(context, database, stateManager, appWidgetId, lat, lon)` | Checks window → queries DAO → evaluates → cooldown gate (15 min) → enqueues one-time work with `KEY_NWS_TERMINAL_CATCH_UP=true` → marks state |
| `scheduleNextCatchUpAttempt(context)` | Builds delayed work (15 min + jitter), enqueues with `REPLACE` policy |
| `cancel(context)` | Cancels unique work by name |

Constants: `COOLDOWN_MS = 15 min`, `SOURCE_KEY = "NWS_TERMINAL_DAY"`, `REFRESH_TYPE = "nws_terminal_catch_up"`

Logging: `NWS_TERMINAL_CATCH_UP_SKIP`, `NWS_TERMINAL_CATCH_UP_REQ`, `NWS_TERMINAL_CATCH_UP_SCHEDULED`, `NWS_TERMINAL_CATCH_UP_CANCELLED`

### Modified File 1: `WeatherWidgetWorker.kt`

Changes made:

1. **New constant**: `KEY_NWS_TERMINAL_CATCH_UP = "nws_terminal_catch_up"` in companion object

2. **Input data reading** (line ~52): Added `val nwsTerminalCatchUp = inputData.getBoolean(KEY_NWS_TERMINAL_CATCH_UP, false)`

3. **Cooldown gate exemption** (line ~74): Added `!nwsTerminalCatchUp` to the condition that skips full background syncs within 5 minutes

4. **New dispatch branch** (after observation backfill, before current-temp-only): If `nwsTerminalCatchUp` is true, calls `handleNwsTerminalCatchUpWork(latitude, longitude, isPlugged, isScreenInteractive)`

5. **New handler method** `handleNwsTerminalCatchUpWork()`:
   - Calls `getWeatherData(forceRefresh=true, targetSourceId="NWS")` for NWS-only fetch
   - Checks if terminal day high is now present via `detectTerminalDayMissingHigh()`
   - If high found: logs `NWS_TERMINAL_CATCH_UP_COMPLETE`
   - If high still missing AND still in window AND charging + screen on: calls `scheduleNextCatchUpAttempt()`
   - Otherwise: logs `NWS_TERMINAL_CATCH_UP_STOP` with reason
   - Repaints all widgets with updated data

6. **Full sync integration** (in `onSuccess` block after `updateAllWidgets()`): If in catch-up window, iterates all widget IDs and calls `maybeEnqueueCatchUp()` — this ensures the hourly periodic sync can also trigger catch-up

### Modified File 2: `WeatherWidgetProvider.kt`

Changes made:

1. **New constant**: `WORK_NAME_NWS_TERMINAL_CATCHUP = "weather_widget_nws_terminal_catch_up"` (line ~723)

2. **Cancellation in `onDisabled()`**: Added `WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_NWS_TERMINAL_CATCHUP)` alongside existing WORK_NAME and WORK_NAME_CURRENT_TEMP cancellation

### Modified File 3: `ScreenOnReceiver.kt`

Changes made:

1. **Screen unlock trigger** (in `handleUserPresent()`, inside existing `goAsync()` coroutine): After scheduling UI updates, checks if charging AND in catch-up window. If so:
   - Gets location from `database.forecastDao().getLatestWeather()`
   - Gets all widget IDs
   - Creates `WidgetStateManager(context)` instance
   - Calls `NwsTerminalDayCatchUpScheduler.maybeEnqueueCatchUp()` for each widget

2. **Screen off cancellation** (in `handleScreenOff()`): Added `NwsTerminalDayCatchUpScheduler.cancel(context)` alongside existing `CurrentTempUpdateScheduler.cancel(context)`

### Bug Fixes During Implementation

1. **Compile error — `appWidgetManager` not in scope**: The full sync completion path tried to use `appWidgetManager` which was only defined inside `updateAllWidgets()`. Fixed by creating a local `val awm = AppWidgetManager.getInstance(context)`.

2. **Compile error — ambiguous `iterator()`**: `IntArray.iterator()` was ambiguous. Fixed by calling `.toList()` on the widget IDs array before iterating.

3. **Non-existent static method**: Initially tried to call `WeatherRepository.getLatestLocationStatic(context)` which doesn't exist. Fixed by calling `database.forecastDao().getLatestWeather()` directly (which is what `getLatestLocation()` does internally).

4. **WidgetStateManager constructor misuse**: Initially passed widget ID as second parameter to constructor (which is actually `appLogDao`). Fixed to use `WidgetStateManager(context)` and rely on companion object methods.

### Test File: `NwsTerminalDayCatchUpPolicyTest.kt`

Created `app/src/test/java/com/weatherwidget/widget/NwsTerminalDayCatchUpPolicyTest.kt`

15 pure JVM tests annotated with `@Category(ShortDuration::class)`:

1. `isInCatchUpWindow returns true during window` — boundary at 18:15, 19:00, 19:29
2. `isInCatchUpWindow returns false before window` — at 18:14, 12:00, 00:00
3. `isInCatchUpWindow returns false at and after window end` — at 19:30, 20:00, 23:59
4. `shouldScheduleCatchUp requires all three conditions` — all 8 combinations (1 true, 7 false)
5. `detectTerminalDayMissingHigh returns null for empty list`
6. `detectTerminalDayMissingHigh returns null when no NWS forecasts` — OPEN_METEO source
7. `detectTerminalDayMissingHigh returns null when terminal day has high` — high present
8. `detectTerminalDayMissingHigh returns info when terminal day has null high and non-null low`
9. `detectTerminalDayMissingHigh returns null when terminal day has null low`
10. `detectTerminalDayMissingHigh only checks furthest future NWS day` — closer day missing high but furthest day has high
11. `detectTerminalDayMissingHigh ignores today and past days`
12. `computeJitteredDelay returns positive value within expected range` — 100 iterations with seeded random
13. `computeJitteredDelay coerces to at least 1 minute` — with baseMinutes=0
14. `computeInitialDelay returns 0 when already in window`
15. `computeInitialDelay returns positive value when before window` — bounds check with 17:00

## 5. Build and Test Results

### Compilation
- `compileDebugKotlin`: **BUILD SUCCESSFUL** (after fixing the two compile errors above)
- `compileDebugUnitTestKotlin`: **BUILD SUCCESSFUL**

### Test Execution
- All 15 `NwsTerminalDayCatchUpPolicyTest` tests: **PASSED**
- Pre-existing test failures (6 total, unrelated): `CurrentTempTouchRoutingRoboTest` (5 failures) and `WidgetIntentRouterHeaderTempRoboTest` (1 failure) — these existed before this session

## 6. Files Created/Modified

### Created
1. `plans/260417-nws-terminal-day-catch-up.md` — Fully specified implementation plan
2. `app/src/main/java/com/weatherwidget/widget/NwsTerminalDayCatchUpPolicy.kt` — Pure decision functions
3. `app/src/main/java/com/weatherwidget/widget/NwsTerminalDayCatchUpScheduler.kt` — Enqueue + loop + cancel
4. `app/src/test/java/com/weatherwidget/widget/NwsTerminalDayCatchUpPolicyTest.kt` — 15 unit tests

### Modified
5. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` — Dispatch branch + handler + full sync trigger
6. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` — Work name constant + onDisabled cancellation
7. `app/src/main/java/com/weatherwidget/widget/ScreenOnReceiver.kt` — Screen unlock trigger + screen off cancellation

## 7. Design Decisions

1. **Stop immediately on unplug/screen off** — Simpler and more battery-friendly. Re-enters on next screen unlock during the window.

2. **Full sync path also triggers catch-up** — Ensures the catch-up runs even if the screen stays on continuously and only periodic syncs fire.

3. **NWS-only fetch** via `targetSourceId="NWS"` in `getWeatherData()` — Other sources not disturbed.

4. **Cooldown via `WidgetStateManager.shouldRefreshMissingData()`** with custom `refreshType = "nws_terminal_catch_up"` — Reuses existing generic cooldown infrastructure; no new WidgetStateManager methods needed.

5. **No changes to `DailyViewLogic.kt`** — Terminal day detection logic reimplemented in `NwsTerminalDayCatchUpPolicy` for the scheduler context (operates on raw forecast lists rather than `weatherByDate` maps).

6. **No changes to `WidgetStateManager.kt`** — Existing `shouldRefreshMissingData()` / `markMissingDataRefreshRequested()` with custom `refreshType` string handles cooldown tracking.

7. **Randomization via `kotlin.random.Random`** — ±3 minute jitter per retry attempt, 0-5 minute initial delay — avoids thundering herd on the NWS API.

## 8. Flow Summary

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
