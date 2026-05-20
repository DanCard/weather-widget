# Session Log: Samsung Charging Temperature Discrepancy (Detailed)
**Date**: Wednesday, April 15, 2026
**Session ID**: 260415-1200-samsung-divergence-audit

## User Prompts & Directives

### Prompt 1
> The current temperature that is shown at top left: how often is it updated when plugged in and charging? On samsung I have two different widgets and a couple of minutes ago, they should values that differed by 0.3

### Prompt 2
> Look at logs, add logging if helpful. I'm not interested in guesses or theories.

### Prompt 3
> write this info to notes/ dir

### Prompt 4
> write a very detailed session log to session-logs/ dir, include all prompts.

---

## Investigation Phase (Empirical Evidence)

### 1. Device Identification
Executed `adb devices` and `getprop ro.product.model` to identify the target hardware:
- **Device**: Samsung Galaxy Z Fold 4 (`SM-F936U1`)
- **Transport**: Wireless ADB (`adb-RFCT71FR9NT-j2OIso._adb-tls-connect._tcp`)

### 2. Log Audit via SQLite
The `weather_database` was pulled from the device using `scripts/backup_databases.py`. The `app_logs` table was audited for `CurrentTempResolver` results and `ui_update_alarm` events.

#### Key Log Sequence (2026-04-15 11:56 - 12:04):
- **11:56:08 (Global Background Update)**: 
    - `ui_update_alarm` fired after being deferred for 13 minutes by Samsung OS.
    - All active widgets updated. 
    - **Widget 349 Result**: `displayTemp=63.750736`
- **11:59:14 (Manual Interaction Update)**:
    - User performed `ACTION_TOGGLE_VIEW` on **Widget 346**.
    - This triggered an isolated refresh path for that specific widget ID.
    - **Widget 346 Result**: `displayTemp=63.950737`
- **The Discrepancy Window**:
    - Between 11:59 and 12:04, **Widget 346** showed ~64.0° while **Widget 349** remained at ~63.8°.
    - The difference (0.2° - 0.3°) was caused by the 3-minute gap in their respective interpolation "snapshots."
- **12:04:13 (Global Re-synchronization)**:
    - The next `ui_update_alarm` fired.
    - Both widgets synchronized to `displayTemp=64.08407`.

### 3. Code Verification
- **`UIUpdateIntervalStrategy.kt`**: Targets a 2-minute (`PLUGGED_IN_MAX_DELAY_MS`) update interval when charging.
- **`UIUpdateScheduler.kt`**: Uses `AlarmManager.setAndAllowWhileIdle`.
- **Finding**: On Samsung devices, `setAndAllowWhileIdle` is aggressively throttled. The logs show the 2-minute target being stretched to 8-13 minutes by the OS.

---

## Technical Conclusion
The desynchronization is not a calculation error but a **lifecycle artifact**. 

1. **OS-Level Batching**: Samsung batches background alarms to save power, causing widgets to rely on "stale" interpolations.
2. **Interaction Drift**: Manual interactions (taps/toggles) force a widget to jump ahead to the current minute's interpolation.
3. **Temporal Gap**: Because the temperature curve is continuously moving, the "interaction-forced" widget and the "OS-deferred" widget show different points on that curve until the next global alarm catches the deferred widget up.

---

## Actions Taken
1. **Technical Note**: Created `notes/260415-samsung-charging-temp-discrepancy.md` documenting the desynchronization behavior.
2. **Analysis Report**: Provided a step-by-step timeline from the device logs explaining the 0.3° difference.
3. **Architecture Recommendation**: Suggested updating `WeatherWidgetProvider.kt` to use `updateAllWidgets` for interaction intents to force cross-widget synchronization.

---

## Implementation Details
- **Tool used**: `sqlite3` for log analysis.
- **Tool used**: `adb` for device discovery and backup.
- **Key file audited**: `app/src/main/java/com/weatherwidget/widget/UIUpdateIntervalStrategy.kt`
- **Key file audited**: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
