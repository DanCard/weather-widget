# Session Log: Friday Divergence and Refresh Scheduling Logging
**Date:** April 17, 2026
**Topic:** Why next Friday diverged across devices, why charging/screen-on did not trigger a refresh, and adding persistent scheduling logs.

## 1. User Prompts Used In This Session
1. `Start in plan-first mode: inspect first, propose a short plan, and wait for approval before making changes. Do not edit files or run mutating commands until I confirm.`
2. `Friday next week is messed up on emulator and samsung, correct on pixel device.  Only has low on samsung and emulator.  Has high and low on samsung.`
3. `Friday next week is messed up on emulator and samsung, correct on pixel device.  Only has low on samsung and emulator.  Has high and low on pixel`
4. `I want to know why the emulator and samsung are missing data.`
5. `How often is data suppose to be refreshed while plugged in, charging, and screen on?  Why isn't there a refresh?`
6. `Can you give me details about when the devices have refreshed or will refresh?  Add to the persistent db log if that info is not available.`
7. `Is that excessive logging or the amount of logging is o.k.?`
8. `yes implement logging plan`
9. `write a detailed session log to session-logs/ dir, include prompts`

## 2. Initial Investigation: Friday Divergence

### Reported Symptom
- The user reported that the next Friday column was wrong on the emulator and Samsung, but correct on the Pixel.
- The clarified final symptom for this session was:
  - **Emulator:** Friday low-only
  - **Samsung:** Friday low-only
  - **Pixel:** Friday high and low

### Runtime Evidence Collected
- Verified connected devices with `adb`:
  - Google Pixel 7 Pro
  - Samsung SM-F936U1
  - Android emulator
- Captured screenshots from Pixel and emulator.
- Screenshot evidence confirmed:
  - Pixel displayed a full Friday column
  - Emulator displayed a low-only Friday column

### Code Paths Inspected
- `DailyViewLogic.kt`
- `NwsForecastMapper.kt`
- `ForecastRepository.kt`
- `WeatherGapIntegrationTest.kt`
- existing session logs from the same day documenting earlier Friday/NWS work

### Database Findings
Read the app databases directly from device copies.

#### Emulator
For `2026-04-24`, emulator had:
- `Generic | 63 / 49 | Historical Avg`
- `NWS | high=null | low=47`
- `SILURIAN | 66 / 48`

Its newest `NWS` batch was:
- `2026-04-17 17:57:03 PDT`
- rows:
  - `2026-04-17 | 72 / null`
  - `2026-04-19 | 74 / 50`
  - `2026-04-20 | 70 / 49`
  - `2026-04-21 | 65 / 52`
  - `2026-04-22 | 66 / 49`
  - `2026-04-23 | 67 / 48`
  - `2026-04-24 | null / 47`

#### Pixel
For `2026-04-24`, Pixel had a newer full NWS Friday row.

Its newest `NWS` batch was:
- `2026-04-17 18:16:40 PDT`
- rows:
  - `2026-04-18 | 76 / 46`
  - `2026-04-19 | 74 / 50`
  - `2026-04-20 | 70 / 49`
  - `2026-04-21 | 65 / 52`
  - `2026-04-22 | 66 / 49`
  - `2026-04-23 | 67 / 48`
  - `2026-04-24 | 70 / 47`

#### Shared Conditions
- Both devices were on the same `Mountain View, CA` NWS location.
- This ruled out location mismatch as the cause.

### Friday Root Cause Conclusion
- The Friday difference was **not** caused by a rendering bug.
- The divergence came from **different newest cached NWS batches**:
  - Pixel had a newer batch containing a full Friday high and low.
  - Emulator had a newer batch that only preserved the terminal Friday low.
- `ForecastRepository.getCachedDataBySource(...)` intentionally selects the **single newest batch** for a source and does not merge it with older fuller source history.
- That makes per-device batch horizon differences directly visible in the widget.

## 3. Refresh Policy Investigation

### User Question
- The user then asked how often refreshes are supposed to happen while plugged in, charging, and screen on, and why a refresh had not happened.

### Relevant Code Inspected
- `ScreenOnReceiver.kt`
- `WeatherWidgetProvider.kt`
- `WeatherWidgetWorker.kt`
- `CurrentTempFetchPolicy.kt`
- `CurrentTempUpdateScheduler.kt`
- `DataFreshness.kt`
- `ForecastStalenessPolicy.kt`
- `BatteryFetchStrategy.kt`

### Actual Refresh Rules Confirmed

#### Full Forecast/Data Fetch
- Scheduled by periodic WorkManager.
- Interval in current code is **1 hour**.
- Entry point:
  - `WeatherWidgetProvider.schedulePeriodicUpdate(...)`

#### Charging Current-Temp Loop
- Scheduled only while:
  - charging
  - screen interactive
- Interval is **10 minutes**.
- This path is **current-temp-only**, not a full multi-day forecast fetch.

#### Screen Unlock Behavior
- Unlock sends `ACTION_REFRESH`.
- A full network-capable path is only taken if data is stale.
- Otherwise the provider does a UI-only refresh from cache.

#### Staleness Gate
- Primary visible source threshold is **60 minutes**.
- Secondary is **90 minutes**.
- Third is **120 minutes**.

### Why There Was No Full Refresh
- Emulator’s latest NWS batch was at `2026-04-17 17:57:03 PDT`.
- Inspection time was `2026-04-17 18:32:48 PDT`.
- That age was only about **36 minutes**.
- Therefore, `DataFreshness.isDataStale(...)` would treat the source as **fresh** and suppress a full forecast fetch.

### Refresh Investigation Conclusion
- Charging + screen on does **not** mean “fetch full forecast immediately.”
- It means:
  - the app may schedule a current-temp loop,
  - unlock may trigger a refresh decision,
  - but a full forecast fetch is still blocked unless the visible source is stale.
- That is why the broken Friday forecast could remain visible despite charging behavior.

## 4. What Was Already Persisted vs Missing

### Already Available In `app_logs`
- `SYNC_START`
- `SYNC_SUCCESS`
- `SYNC_SKIP`
- `CURR_FETCH_START`
- `CURR_FETCH_DONE`
- `CURR_FETCH_SKIP`
- `CURR_FETCH_LOOP_STOP`
- `UNLOCK_REFRESH_POLICY`
- `POWER_CONNECTED_EVENT`
- `NWS_BATCH_RENDER_SUMMARY`

These logs already made it possible to reconstruct:
- recent refresh history
- whether a current-temp fetch ran or was skipped
- whether unlock policy chose UI-only
- whether the charging loop was stopped by policy

### Missing From `app_logs`
- the exact future scheduled time for:
  - next current-temp work enqueue
  - next periodic refresh window
- this information was only recoverable by reading WorkManager’s persisted database:
  - `no_backup/androidx.work.workdb`

### WorkManager Inspection
- Copied `androidx.work.workdb`, `-wal`, and `-shm` from emulator and Pixel.
- Confirmed relevant named work rows existed:
  - `weather_widget_update`
  - `weather_widget_current_temp`
  - `weather_widget_one_time`
  - `weather_widget_one_time_ui`

This proved the future-scheduling data existed on-device, but was not mirrored into the app’s own persistent diagnostics.

## 5. Logging Design Decision

### User Asked
- whether adding more logging would be excessive
- and then explicitly approved implementation

### Logging Strategy Chosen
- Keep logging **decision-based** and compact.
- Do **not** add continuous heartbeat spam.
- Add only:
  - enqueue/cancel events for current-temp work
  - periodic refresh scheduling event
  - refresh path decision with stale-source summary

This kept the logging volume aligned with the existing debug-oriented app design.

## 6. Implementation

### Files Changed
1. `app/src/main/java/com/weatherwidget/widget/DataFreshness.kt`
2. `app/src/main/java/com/weatherwidget/widget/CurrentTempUpdateScheduler.kt`
3. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`

### Production Changes

#### `DataFreshness.kt`
Added:
- `getVisibleSourceFreshnessSummary(context)`

Behavior:
- Produces a compact per-source summary such as:
  - source id
  - age in minutes
  - threshold in minutes
  - `fresh` vs `stale`
- This is designed for persisted decision logs rather than verbose diagnostics.

#### `CurrentTempUpdateScheduler.kt`
Added persistent logs:
- `CURR_FETCH_WORK_ENQUEUED`
- `CURR_FETCH_WORK_CANCELLED`

Logged fields include:
- enqueue type (`immediate` vs `charging_loop`)
- reason
- opportunistic / force flags
- policy delay minutes
- due time
- WorkManager work id

#### `WeatherWidgetProvider.kt`
Added persistent logs:
- `REFRESH_DECISION`
- `PERIODIC_REFRESH_SCHEDULE`

`REFRESH_DECISION` includes:
- whether UI-only was requested
- charging / interactive state
- stale result
- visible-source freshness summary

`PERIODIC_REFRESH_SCHEDULE` includes:
- work name
- interval minutes
- policy mode
- next expected window start time in milliseconds

## 7. Verification

### Focused Test Run
Executed:
```bash
./gradlew testDebugUnitTest --tests com.weatherwidget.widget.ScreenOnReceiverTest
```

### Result
- `BUILD SUCCESSFUL`
- Relevant Robolectric screen-on / power-connected tests passed.

## 8. Final Outcome

By the end of the session:
1. the Friday divergence was traced to **device-specific newest NWS batch differences**, not a rendering bug,
2. the lack of refresh while charging/screen-on was explained by the **staleness gate** and the distinction between **current-temp-only** work and **full forecast** fetches,
3. refresh history was shown to already be mostly recoverable from persistent `app_logs`,
4. future scheduling visibility was identified as the main diagnostics gap,
5. compact persistent scheduling logs were added to close that gap without introducing excessive log volume.

## 9. Unrelated Local State Left Untouched
- The repo had untracked local files:
  - `samsung_wd_fixed`
  - `samsung_wd_fixed-shm`
  - `samsung_wd_fixed-wal`
- These were not modified.
