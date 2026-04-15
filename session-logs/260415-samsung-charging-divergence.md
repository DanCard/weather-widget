# Session Log: Samsung Charging Temperature Discrepancy Investigation
**Date**: Wednesday, April 15, 2026
**Session ID**: 260415-samsung-charging-divergence

## Objective
Investigate why two widgets on a Samsung device (SM-F936U1) showed a 0.3° temperature difference while the device was plugged in and charging.

## Evidence Collection
1.  **Device Info**: Samsung Galaxy Z Fold 4 (SM-F936U1).
2.  **Database Audit**: Pulled the `weather_database` from the device and analyzed the `app_logs` table.
3.  **Key Findings from Logs**:
    -   **Update Cadence**: While the code in `UIUpdateIntervalStrategy.kt` targets a 1-2 minute update interval when charging, the logs showed the `ui_update_alarm` firing much less frequently:
        -   `11:43:24`
        -   `11:56:06` (12m 42s gap)
        -   `12:04:13` (8m 07s gap)
    -   **Divergence Event**:
        -   **11:56:08**: A global update occurred. Widget `349` resolved to **63.75°**.
        -   **11:59:14**: The user interacted with Widget `346` (ACTION_TOGGLE_VIEW). This triggered an immediate, isolated refresh for Widget `346` only.
        -   **Result**: Widget `346` calculated a new interpolation for 11:59:14, which was **63.95°**. Widget `349` remained at its 11:56:08 value (**63.75°**) because the system-level background alarm was deferred.
    -   **Re-synchronization**:
        -   **12:04:13**: The next global `ui_update_alarm` finally fired. Both widgets (345, 346, 349) were updated simultaneously and synchronized to **64.08°**.

## Technical Root Cause
The discrepancy is an artifact of **OS-level alarm batching** combined with **per-widget interaction triggers**.

1.  **Samsung Power Management**: The widget uses `AlarmManager.setAndAllowWhileIdle` for its 2-minute charging UI updates. Samsung's aggressive power management ignores the 2-minute request and batches these alarms into roughly 8-15 minute windows.
2.  **Isolated Refresh Logic**: Tapping a widget (or resizing it) triggers an immediate update only for the `appWidgetId` associated with that intent.
3.  **Temporal Drift**: Because the current temperature is a time-based interpolation between forecast hours, even a 3-minute gap in refresh time can lead to a ~0.2° - 0.4° difference when the temperature curve is steep.

## Conclusion
The interpolation math is correct and consistent across widgets. The desynchronization is purely a side effect of one widget being "pulled forward" in time by a user interaction while the other remains "frozen" in the past by the Android OS's background execution limits.

## Recommendations
- **Broadcasting Updates**: Modify user interaction handlers in `WeatherWidgetProvider.kt` to trigger a global update for all widgets (`updateAllWidgets`) rather than just the target widget. This ensures that any manual interaction snaps all widgets to the same current-time interpolation.
- **Improved Logging**: Added `RESOLVE_RESULT` logging in previous sessions was critical for this investigation. Keep detailed "resolve:explain" logs to debug future temporal drift issues.

## Files Referenced
- `app/src/main/java/com/weatherwidget/widget/UIUpdateIntervalStrategy.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `notes/260415-samsung-charging-temp-discrepancy.md`
