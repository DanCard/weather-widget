# Charging + Screen-On Update Interval Analysis

Date: 2026-04-17

## Summary

When the device is charging and the screen is on, three distinct update loops are active. The full forecast is NOT fetched every hour as commonly assumed — only a lightweight current-temperature observation runs at high frequency. The full forecast fetch runs on a separate hourly periodic job.

## Active Update Loops

### 1. Current-Temp Network Fetch — Every 10 Minutes

- **Constant:** `CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES = 10L`
- **Source:** `CurrentTempFetchPolicy.kt:7`
- **Scheduler:** `CurrentTempUpdateScheduler.scheduleNextChargingUpdate()` (`CurrentTempUpdateScheduler.kt:73`)
- **What it does:** Fetches only the current observation / interpolated temperature from the API. This is a lightweight single-endpoint hit, not the full forecast.
- **Loop guard:** After each run, `WeatherWidgetWorker.manageCurrentTempLoopAfterRun()` checks `CurrentTempFetchPolicy.shouldScheduleChargingLoop(isPlugged, isScreenInteractive)` — the loop only continues while both charging AND screen interactive are true (`CurrentTempFetchPolicy.kt:33-36`).
- **Trigger:** Started on screen unlock while charging (`ScreenOnReceiver.kt:91-96`), and on power connect (`ScreenOnReceiver.kt:60-64`). Stopped on screen off (`ScreenOnReceiver.kt:143-147`).

### 2. UI-Only Refresh from Cache — Every 1–2 Minutes

- **Constant:** `UIUpdateIntervalStrategy.PLUGGED_IN_MAX_DELAY_MS = 2 * 60 * 1000L` (2 minutes)
- **Source:** `UIUpdateIntervalStrategy.kt:8`
- **What it does:** Re-renders the widget from cached hourly data. No network request. This keeps the displayed temperature smoothly tracking the interpolated value between full fetches.
- **How it works:** `UIUpdateScheduler.scheduleNextUpdate()` calculates a delay based on temperature change rate, but caps it at `PLUGGED_IN_MAX_DELAY_MS` when charging (`UIUpdateScheduler.kt:74-85`, `UIUpdateIntervalStrategy.kt:23-27`). Minimum delay is 1 minute (`MINIMUM_DELAY_MS`).
- **Alarm type:** `AlarmManager.setAndAllowWhileIdle()` — opportunistic, no guaranteed wakeup (`UIUpdateScheduler.kt:118-126`).

### 3. Full Forecast Fetch — Every 1 Hour

- **Source:** `WeatherWidgetProvider.kt:660-685`
- **What it does:** Periodic WorkManager job that fetches all daily forecasts, hourly forecasts, forecast snapshots, and observation data. This is the heavy fetch.
- **Constraint:** `ExistingPeriodicWorkPolicy.KEEP` — if a fetch is already scheduled, it won't create a duplicate.

## Interaction on Screen Unlock

When the user unlocks the screen while charging (`ScreenOnReceiver.handleUserPresent()`):

1. Immediate UI refresh from cache for instant feedback
2. Immediate current-temp network fetch enqueued (`CurrentTempUpdateScheduler.enqueueImmediateUpdate`)
3. Next UI-only alarm scheduled via `UIUpdateScheduler`
4. If in NWS terminal-day catch-up window, a catch-up fetch may also be triggered

## Is It Too Often?

No. The intervals are well-calibrated for a charging scenario where battery impact is nil:

- **10-minute current-temp fetch:** Weather observations can change noticeably in 10 minutes (gusts, precipitation onset). NWS updates its observation records roughly hourly, but Open-Meteo can update more frequently. A 10-minute poll is a reasonable balance.
- **1–2 minute UI re-render:** Purely local (no network), just recalculates the interpolated temperature position. Negligible cost.
- **1-hour full forecast:** Matches NWS grid update cadence. Fetching more often would just return duplicate data.

The system also properly stops the loops when the screen turns off or the device is unplugged (`ScreenOnReceiver.kt:143-147`), preventing wasted work.
