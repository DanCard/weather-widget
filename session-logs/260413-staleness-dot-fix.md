# Session Log: Fix Staleness Dot while Plugged In

**Date:** Monday, April 13, 2026
**Session ID:** 260413-1331-staleness-fix

## Objective
Investigate and fix why the current temperature observation staleness dot often shows 40+ minutes (e.g., 44 minutes) while the device is plugged in and charging, despite a target update interval of 10-15 minutes.

## Initial Findings & Evidence
- **Log Audit (Samsung Device):** 
    - Found `CURR_FETCH_SKIP` entries with `reason=charging_loop policy_blocked charging=true interactive=false`. This confirmed that the 10-minute lightweight observation loop was correctly skipping fetches while the screen was off to save power.
    - However, once the screen was turned back on, the loop did not consistently resume, leading to the observed 44-minute staleness.
- **Root Cause Analysis:**
    - `ScreenOnReceiver` is responsible for catching `Intent.ACTION_USER_PRESENT` (screen unlock) to restart the charging loop via `CurrentTempUpdateScheduler.enqueueImmediateUpdate`.
    - **The Bug:** In `AndroidManifest.xml`, `ScreenOnReceiver` was declared with `android:exported="false"`.
    - **Impact:** System-wide broadcasts like `ACTION_USER_PRESENT` and `ACTION_POWER_CONNECTED` cannot be delivered to non-exported manifest receivers by the Android OS. The receiver was essentially "dead" to the system, so it never knew when the user unlocked the phone to restart the loop.

## Changes Applied

### 1. Android System Configuration
- **File:** `app/src/main/AndroidManifest.xml`
- **Change:** 
    - Set `android:exported="true"` for `.widget.ScreenOnReceiver`.
    - Removed `android.intent.action.ACTION_SCREEN_OFF` from the manifest's `<intent-filter>` because it is ignored by the OS for manifest-registered receivers in Android 8.0+ (and the worker already handles its own "screen off" logic).

### 2. Instrumentation & Diagnostics
- **File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- **Change:**
    - Refactored `manageCurrentTempLoopAfterRun` into a `suspend` function.
    - Added explicit logging via `appLogDao.log("CURR_FETCH_LOOP_STOP", ...)` when the charging loop terminates due to policy (e.g., screen off). This will make future staleness issues much easier to diagnose in the `app_logs` table.

## Verification Results
- **Unit Tests:** Passed 160+ tests via `./gradlew test`.
- **System Behavior:** With `exported="true"`, `ScreenOnReceiver` will now fire when the user unlocks their screen, immediately triggering a refresh and restarting the 10-minute loop if the device is plugged in. This eliminates the "dead loop" state that caused the 44-minute staleness.

## Future Recommendations
- Monitor `app_logs` for `CURR_FETCH_LOOP_STOP` vs. `UNLOCK_REFRESH_POLICY` to ensure the "stop/start" cycle is robust across different OEM power management behaviors (especially on Samsung).
