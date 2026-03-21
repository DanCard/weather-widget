# Session Notes: Current Temperature Update Frequency Analysis

## Date
- 2026-03-21

## User Question
- How often does the widget wake up the device to update current temperature?

## Summary Answer
- The widget does not normally wake the device on a fixed schedule just to redraw the interpolated current temperature.
- The normal current-temperature UI path is opportunistic and uses `AlarmManager` in a way intended to piggyback on times the device is already awake.
- There is also a 30-minute opportunistic `JobScheduler` path on Android 8+ that is explicitly documented as piggybacking on existing system wakeups.
- The only fixed, dedicated current-temperature fetch loop is a 10-minute `WorkManager` loop, and that loop only continues while the device is charging and the screen is interactive.

## Detailed Findings

## Charge Vs Battery

### Battery power
- UI-only current-temperature redraws still occur, but through opportunistic scheduling rather than a deliberate wakeup loop.
- Practical redraw cadence is based on hourly temperature change:
  - 0-1 degree change => every 60 minutes
  - 2-3 degree change => every 30 minutes
  - 4-5 degree change => every 20 minutes
  - 6+ degree change => every 15 minutes
- On battery, there is no dedicated repeating 10-minute current-temp fetch loop.
- On battery, current-temp network fetches are allowed only in opportunistic contexts according to `CurrentTempFetchPolicy.shouldFetchNow(...)`.
- The Android 8+ opportunistic job still exists on battery:
  - nominally every 30 minutes
  - intended to piggyback on times the system is already awake
  - not intended to wake the device just to refresh current temperature

### Charging power
- When charging, the UI-only redraw path becomes more aggressive:
  - `UIUpdateIntervalStrategy` caps the redraw delay at 2 minutes
  - so the widget can redraw the interpolated current temperature roughly every 1-2 minutes while charging
- When charging and the screen is interactive, the app also runs a dedicated current-temp-only network loop:
  - fixed interval of 10 minutes
  - implemented through `CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES = 10L`
  - rescheduled after each run by `WeatherWidgetWorker.manageCurrentTempLoopAfterRun(...)`
- If charging stops or the screen turns off, that 10-minute loop is canceled.

### Bottom line
- On battery:
  - no dedicated fixed current-temp wakeup loop
  - UI redraw cadence is 15/20/30/60 minutes, opportunistic
  - opportunistic system job runs about every 30 minutes
- While charging and screen-on:
  - UI redraw delay is capped at 2 minutes
  - current-temp network refresh can run every 10 minutes
- While charging but not interactive:
  - the repeating 10-minute current-temp loop should not continue
  - only opportunistic mechanisms remain relevant
### 1. UI-only current-temperature redraws
- File: `app/src/main/java/com/weatherwidget/widget/UIUpdateScheduler.kt`
- Purpose: redraw the widget using cached hourly data and interpolated temperature, without forcing a network fetch.
- Behavior:
  - Reads the current hour and next hour temperatures from stored hourly forecast data.
  - Computes the difference between those two temperatures.
  - Uses that difference to determine the next redraw time.
  - Schedules the next redraw through `AlarmManager`.

### 2. Actual cadence for the UI-only redraw path
- Files:
  - `app/src/main/java/com/weatherwidget/util/TemperatureInterpolator.kt`
  - `app/src/main/java/com/weatherwidget/widget/UIUpdateIntervalStrategy.kt`

- Base rule table from `TemperatureInterpolator.getUpdatesPerHour(tempDifference)`:
  - `absDiff >= 6` => 4 updates per hour => every 15 minutes
  - `absDiff >= 4` => 3 updates per hour => every 20 minutes
  - `absDiff >= 2` => 2 updates per hour => every 30 minutes
  - otherwise => 1 update per hour => every 60 minutes

- Boundary scheduling from `TemperatureInterpolator.getNextUpdateTime(...)`:
  - The code snaps to the next interval boundary within the current hour.
  - Examples:
    - At `10:07`, with a 20-minute cadence, next update is `10:20`
    - At `10:41`, with a 15-minute cadence, next update is `10:45`
    - At `10:50`, with an hourly cadence, next update is `11:00`

- Additional caps from `UIUpdateIntervalStrategy.computeDelayMillis(...)`:
  - Minimum delay is 1 minute.
  - If charging, delay is capped at 2 minutes.
  - If 6:00 PM (evening mode transition) happens sooner than the computed delay, schedule for 6:00 PM instead.

- Fallback:
  - If hourly forecast data is unavailable, `UIUpdateScheduler` falls back to 30 minutes.
  - If scheduling logic throws, it also falls back to 30 minutes.

## Wakeup Behavior

### 1. AlarmManager UI path
- `UIUpdateScheduler.scheduleUpdate(...)` uses:
  - `alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, ...)` on API 23+
  - `alarmManager.set(AlarmManager.RTC, ...)` on older devices
- The class comment and inline comment both describe this as:
  - opportunistic
  - no guaranteed wakeup
  - intended to fire when the device is already awake

- Conclusion:
  - The normal interpolated current-temp redraw path is not intended as a deliberate device wakeup source.
  - Practical cadence on battery is usually one of:
    - 60 minutes
    - 30 minutes
    - 20 minutes
    - 15 minutes
  - But this is still opportunistic, not a strict "wake the phone every N minutes" policy.

### 2. Opportunistic JobScheduler path
- File: `app/src/main/java/com/weatherwidget/widget/OpportunisticUpdateJobService.kt`
- The job is scheduled with:
  - `setPeriodic(TimeUnit.MINUTES.toMillis(30))`
- The class comment states that it:
  - piggybacks on system wakeups
  - does not create independent wakeups
  - is scheduled to run periodically but only when the device is already awake

- Behavior when it runs:
  - If recent hourly data exists, enqueue a UI-only widget refresh.
  - Also enqueue a current-temp-only worker in opportunistic mode.

- Conclusion:
  - Nominal cadence is every 30 minutes.
  - It is not intended to wake a sleeping device just for current-temperature work.

### 3. Charging current-temp network loop
- Files:
  - `app/src/main/java/com/weatherwidget/widget/CurrentTempFetchPolicy.kt`
  - `app/src/main/java/com/weatherwidget/widget/CurrentTempUpdateScheduler.kt`
  - `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`

- Fixed interval:
  - `CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES = 10L`

- Scheduling:
  - `CurrentTempUpdateScheduler.scheduleNextChargingUpdate(...)` creates a `OneTimeWorkRequest` with:
    - `setInitialDelay(10 minutes)`
    - `KEY_CURRENT_TEMP_ONLY = true`

- Guard condition:
  - `CurrentTempFetchPolicy.shouldScheduleChargingLoop(...)` returns true only when:
    - `isCharging == true`
    - `isScreenInteractive == true`

- Worker follow-up:
  - After a current-temp-only run, `WeatherWidgetWorker.manageCurrentTempLoopAfterRun(...)` either reschedules the next 10-minute run or cancels the loop based on those conditions.

- Conclusion:
  - There is a real 10-minute current-temp refresh loop.
  - It only runs while charging and interactive.
  - Because it only persists in that state, it is not best described as "waking a sleeping device every 10 minutes."

## Separate Full-Forecast Worker
- File: `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- There is also a periodic full worker scheduled hourly:
  - `PeriodicWorkRequestBuilder<WeatherWidgetWorker>(1, TimeUnit.HOURS)`
- This is broader than the dedicated current-temperature update path and should not be confused with the current-temp-only loop.

## Direct User-Facing Answer Captured
- Sleeping device:
  - effectively no intentional fixed current-temp wakeup loop for UI redraws
- Opportunistic current-temp refresh activity:
  - around every 30 minutes via system-opportunistic mechanisms
- Charging + screen-on:
  - current-temp-only network refresh can run every 10 minutes
- UI redraw cadence from cached hourly data:
  - 60 / 30 / 20 / 15 minutes depending on the current-hour to next-hour temperature difference

## Code Locations Confirmed During Session
- `app/src/main/java/com/weatherwidget/widget/UIUpdateScheduler.kt`
- `app/src/main/java/com/weatherwidget/widget/UIUpdateIntervalStrategy.kt`
- `app/src/main/java/com/weatherwidget/util/TemperatureInterpolator.kt`
- `app/src/main/java/com/weatherwidget/widget/OpportunisticUpdateJobService.kt`
- `app/src/main/java/com/weatherwidget/widget/CurrentTempUpdateScheduler.kt`
- `app/src/main/java/com/weatherwidget/widget/CurrentTempFetchPolicy.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`

## Key Clarification From Session
- The phrase "Typical cadence: variable" was refined to the explicit rule table:
  - 0-1 degree change => 60 minutes
  - 2-3 degree change => 30 minutes
  - 4-5 degree change => 20 minutes
  - 6+ degree change => 15 minutes
- Charging further caps UI redraw delay to 2 minutes, while the separate current-temp-only network loop uses a 10-minute interval.
- The session note now explicitly separates:
  - battery behavior
  - charging + interactive behavior
  - charging but non-interactive behavior
