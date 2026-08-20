# Plan: Desktop Screen-State Aware Temperature Actuals Policy

**Author:** Gemini  
**Date:** 2026-08-20  
**Target:** Desktop (`:desktop`) & Shared (`:shared`)

---

## 1. Overview & Goals

This plan specifies the implementation of a screen-state and interaction-aware policy for temperature actuals (observations) in the Linux Desktop application:

1. **Plugged in + Screen OFF:** Fetch temperature actuals once every **30 minutes** (instead of every 10 minutes).
2. **Screen Turn-ON / User Interaction Catch-up (Interrupt & Event-Driven, No Polling Fallback):**
   * **D-Bus Screen Wake Signal:** When the display wakes (`ScreenSaver.ActiveChanged(false)`), trigger an immediate observation fetch if $> 10$ minutes have elapsed since the last update.
   * **Genmon Applet Click (`.show` trigger):** When the panel applet is clicked, trigger a catch-up fetch if $> 10$ minutes have elapsed.
   * **UI Process Launch / Focus (`.ui-show`):** When the desktop window opens/focuses, ensure data $> 10$ minutes old is refreshed.
   * **System Resume (`login1 PrepareForSleep(false)`):** Existing wake kick covers resume-from-suspend.
3. **Non-Primary Sources:** Defer / skip non-primary (inactive) observation fetches completely while the screen is OFF.

---

## 2. Architecture & Component Analysis

### Current State
* **[`DesktopFetchStrategy.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopFetchStrategy.kt):**
  * `getObservationRefreshDelayMs(isCharging, batteryLevel)` uses a constant 10-minute interval on AC (`AC_OBSERVATION_MINUTES = 10L`) without checking `screenOn`.
* **[`ScreenStateDetector.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/ScreenStateDetector.kt):**
  * Provides on-demand `isScreenOn()` via X11 DPMS (`xset -q`) and logind (`loginctl`).
* **[`DaemonProcess.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonProcess.kt):**
  * **Loop 3b (Temp actuals loop):** Runs on fixed delays from `getObservationRefreshDelayMs`.
  * **Loop 3d (Non-primary actuals loop):** Uses `NonPrimaryObservationPolicy.intervalMinutes(isCharging, screenOn)` (only runs when `isCharging && screenOn`).
  * **WatchService Triggers:** Listens for `.show` (genmon click), `.refresh-requested`, `.config-changed`.
  * **D-Bus Monitors:** Already monitors `org.freedesktop.login1` (system sleep/resume) and `NetworkManager`.

---

## 3. Detailed Implementation Steps

### Step 1: Update `DesktopFetchStrategy`

Add `screenOn` support and the catch-up staleness evaluation function:

```kotlin
object DesktopFetchStrategy {
    private const val MS_PER_MINUTE = 60 * 1000L

    // AC Power Intervals
    const val AC_OBSERVATION_SCREEN_ON_MINUTES = 10L
    const val AC_OBSERVATION_SCREEN_OFF_MINUTES = 30L
    const val CATCH_UP_STALENESS_THRESHOLD_MINUTES = 10L

    /**
     * Returns the delay in MS for the next observation fetch.
     */
    fun getObservationRefreshDelayMs(isCharging: Boolean, batteryLevel: Int, screenOn: Boolean): Long? {
        if (isCharging) {
            val minutes = if (screenOn) AC_OBSERVATION_SCREEN_ON_MINUTES else AC_OBSERVATION_SCREEN_OFF_MINUTES
            return minutes * MS_PER_MINUTE
        }

        return when {
            batteryLevel > BatteryTier.TIER_HIGH_THRESHOLD -> BatteryTier.INTERVAL_HIGH_MINUTES * MS_PER_MINUTE
            batteryLevel > BatteryTier.TIER_MEDIUM_THRESHOLD -> BatteryTier.INTERVAL_MEDIUM_MINUTES * MS_PER_MINUTE
            else -> null
        }
    }

    /**
     * Pure function: Returns true if an immediate catch-up observation fetch is needed
     * upon screen wake or user interaction.
     */
    fun shouldCatchUpObservations(
        lastFetchMs: Long?,
        nowMs: Long,
        stalenessThresholdMs: Long = CATCH_UP_STALENESS_THRESHOLD_MINUTES * MS_PER_MINUTE
    ): Boolean {
        if (lastFetchMs == null) return true
        return (nowMs - lastFetchMs) >= stalenessThresholdMs
    }
}
```

---

### Step 2: Event-Driven Triggers in `DaemonProcess.kt` (No Fallback Polling)

Instead of a polling loop checking the screen state repeatedly, we hook into pure **signals and user events**:

1. **D-Bus Screen-On Signal Monitor (Interrupt-Driven):**
   * Launch a background monitor on the session bus for screensaver/lock changes:
     ```bash
     gdbus monitor --session --dest org.freedesktop.ScreenSaver --object-path /org/freedesktop/ScreenSaver
     ```
   * Parse `ActiveChanged(false)` (screen unblanked / unlocked).
   * When received, call `kickObservationCatchUp("screen:unblank")`.

2. **Genmon Click (`.show` Trigger Event):**
   * In `DaemonProcess.kt` under the `SHOW_TRIGGER` watch handler (where genmon clicks land):
     ```kotlin
     SHOW_TRIGGER -> {
         kickObservationCatchUp("genmon:click")
         // existing UI spawn / focus logic...
     }
     ```

3. **UI Process Launch / Focus Event (`.ui-show`):**
   * When the UI is brought to the foreground or launched, `kickObservationCatchUp("ui:show")` ensures data is immediately refreshed if $> 10$ minutes old.

4. **`kickObservationCatchUp(reason: String)` Helper:**
   * Checks `DesktopFetchStrategy.shouldCatchUpObservations(lastObservationFetchMs, nowMs)`.
   * Debounced (e.g. 15-second window) so multiple rapid clicks/events do not trigger duplicate network fetches.
   * Runs `repo.refreshObservations()` asynchronously and publishes the new state to the panel and UI channel.

5. **Loop 3b Update:**
   * In the main observation fetch loop, query `ScreenStateDetector.isScreenOn()` only once per cycle to set the sleep delay (10m vs 30m).

---

### Step 3: Non-Primary Sources Verification

* Confirm that `Loop 3d` (Non-Primary Actuals) continues to use `NonPrimaryObservationPolicy.intervalMinutes(isCharging, screenOn)`.
* When `screenOn == false`, `intervalMinutes` returns `null`, ensuring non-primary sources are completely skipped when the display is off.

---

### Step 4: Unit & Integration Testing

1. **`DesktopFetchStrategyTest.kt`:**
   * Test AC + Screen ON -> 10 min (600,000 ms).
   * Test AC + Screen OFF -> 30 min (1,800,000 ms).
   * Test Battery + Screen ON/OFF -> Battery tier intervals.
   * Test `shouldCatchUpObservations`:
     * Returns `true` when `lastFetchMs` is `null`.
     * Returns `false` when last fetch was 5 minutes ago.
     * Returns `true` when last fetch was 11 minutes ago.
2. **`ScreenStateDetectorTest.kt`:**
   * Test D-Bus signal parsing (e.g. `ActiveChanged(false)`).
   * Test `xset -q` and `loginctl` parser helpers.
3. **Category Requirements:**
   * Annotate tests with `@Category(ShortDuration::class)` per project conventions.

---

## 4. Verification & Testing Steps

1. **Run Desktop Unit Tests:**
   ```bash
   ./gradlew :desktop:test
   ```
2. **Run All Unit Tests:**
   ```bash
   ./gradlew test
   ```
3. **Manual Verification:**
   * Launch desktop daemon (`scripts/buildStart.sh`).
   * Verify with `sqlite3 ~/.local/share/weather-widget/weather.db "SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT 20;"` that:
     * Screen-off periods space out observation fetches to 30 min.
     * Clicking the genmon panel icon or waking screen triggers `kickObservationCatchUp` if $> 10$ minutes old.
