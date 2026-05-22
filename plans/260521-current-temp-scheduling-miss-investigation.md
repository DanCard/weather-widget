# Investigate and Hardened Pixel Pro Current Temp Update Cadence

## Objective
The Pixel 7 Pro experienced a 17-minute gap in current temperature updates from the NWS API, despite being on a 10-minute charging loop. This investigation points to a long-running NWS retry loop (up to 7 minutes) which can delay the scheduling of the successor worker. We will add diagnostic logging to confirm this behavior and reduce the retry aggressiveness to ensure more predictable update cycles.

## Proposed Changes

### 1. Diagnostic Logging: Current Temp Fetch Lifecycle
**File**: `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`
- Add `CURR_FETCH_START` log with the reason and thread ID.
- Add `CURR_FETCH_COMPLETE` log with total duration, success count, and sources attempted.

**File**: `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
- Add logging inside `fetchNwsCurrent` to track the start of the retry loop.
- Log each retry attempt in `fetchStationObservation` with more detail (station name, attempt number, status).
- Log the final outcome of `fetchNwsCurrent` including total duration.

### 2. Diagnostic Logging: Scheduler Decisions
**File**: `app/src/main/java/com/weatherwidget/widget/CurrentTempUpdateScheduler.kt`
- Add logging for the current battery and screen state when `scheduleNextChargingUpdate` is called.
- Ensure `decideChargingLoopWork` logs the `ignoreRunningWorkId` if present.

### 3. Hardening: Reduce NWS Retry Aggressiveness
**File**: `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
- Reduce `retryDelaysMs` from `listOf(60_000L, 120_000L, 240_000L)` (7 minutes) to `listOf(10_000L, 30_000L)` (40 seconds).
- Reason: For a 10-minute loop, a 40-second retry is sufficient; if NWS is down longer, the next 10-minute worker will handle it. This prevents workers from lingering as "RUNNING" for most of their interval.

### 4. Hardening: Worker Cancellation on Screen-Off
**File**: `app/src/main/java/com/weatherwidget/widget/ScreenOnReceiver.kt`
- Ensure that if the screen turns off, any active `WORK_NAME_CURRENT_TEMP` is cancelled immediately to prevent lingering "ghost" workers from blocking new ones when the screen turns back on.

## Verification
- Monitor the `app_logs` table for `CURR_FETCH_START` and `CURR_FETCH_COMPLETE` durations.
- Verify that NWS failures result in much faster worker completion (approx 1 minute max instead of 8 minutes).
- Trigger manual refreshes and verify they still succeed immediately and reset the loop correctly.
