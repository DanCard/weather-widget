# Summary: Desktop Screen-State & Interaction-Aware Temperature Actuals Fetch Policy

**Date:** 2026-08-20  
**Module:** `:desktop`  

---

## Overview

Implemented a dynamic observation (temperature actuals) fetch scheduling policy for the Linux Desktop application that:
1. Backs off the periodic observation polling interval from 10 minutes to **30 minutes** when the device is plugged in (AC) but the display is **OFF / Blanked / Locked**.
2. Performs an **immediate catch-up observation fetch** when the display wakes up or the user interacts with the app (clicking the genmon panel applet or launching/focusing the UI) if the data is older than **10 minutes**.
3. Keeps non-primary / inactive source observation fetches suspended when the screen is OFF.

---

## Changes Implemented

### 1. Updated Fetch Scheduling Policy (`DesktopFetchStrategy.kt`)
* Added `screenOn: Boolean = true` parameter to `getObservationRefreshDelayMs(isCharging, batteryLevel, screenOn)`:
  * `isCharging && screenOn` $\rightarrow$ **10 minutes** (`AC_OBSERVATION_SCREEN_ON_MINUTES`).
  * `isCharging && !screenOn` $\rightarrow$ **30 minutes** (`AC_OBSERVATION_SCREEN_OFF_MINUTES`).
  * `!isCharging` $\rightarrow$ Respects battery tiers (Tier 1: 240 min, Tier 2: 480 min, $\le 50\%$: suspended).
* Added pure evaluation function `shouldCatchUpObservations(lastFetchMs, nowMs, thresholdMs)` (default threshold: 10 minutes).

### 2. Event-Driven Wake & Interaction Triggers (`DaemonProcess.kt`)
* **D-Bus Screen Wake Signal Monitor:** Added a persistent session bus monitor for `org.freedesktop.ScreenSaver` listening for `ActiveChanged (false)` (screen unblanked / unlocked) which invokes `kickObservationCatchUp("screensaver:wake")`.
* **Genmon Click Trigger:** Updated the `.show` WatchService handler to invoke `kickObservationCatchUp("genmon:click")`.
* **Debounce & Gating:** Added `kickObservationCatchUp(reason)` with a 15-second debounce window that evaluates `shouldCatchUpObservations` before triggering a background `newRepo.refreshObservations()`.
* **Periodic Loop (3b):** Now samples `ScreenStateDetector.isScreenOn()` each cycle to schedule the next sleep duration (10m vs 30m).

### 3. Signal Parser & Constants (`DesktopProcess.kt`)
* Added `isScreenWakeSignalLine(line: String)` to parse D-Bus `ActiveChanged (false)` signals.
* Added `OBSERVATION_CATCH_UP_DEBOUNCE_MS = 15_000L`.

### 4. Unit Tests
* **`DesktopFetchStrategyTest.kt`:** Added test cases for AC Screen On (10 min), AC Screen Off (30 min), and catch-up staleness evaluations ($< 10$ min, $\ge 10$ min, `null`).
* **`RefreshDelayTest.kt`:** Added unit tests verifying `isScreenWakeSignalLine` accurately parses active-false lines and rejects active-true and unrelated D-Bus traffic.

---

## Verification
* Executed `./scripts/unit-tests.sh`: **3,154 tests passed** across `:desktop`, `:shared`, and `:app`.
