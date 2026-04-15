# Samsung Charging Temperature Discrepancy
**Date**: 2026-04-15

## Issue
When a Samsung device is plugged in and charging, two widgets on the same home screen may show slightly different current temperatures (e.g., a 0.3° difference). 

## Update Mechanisms
While charging, the current temperature is updated via two distinct mechanisms:
1. **Lightweight UI Redraw (Interpolated)**: Designed to fire every 1 to 2 minutes. The widget recalculates and redraws a smooth, "interpolated" current temperature based on the curve between recent history and the upcoming forecast.
2. **Network Data Fetch (Actuals)**: Fires every 10 minutes, but only if the screen is interactive, to grab a fresh actual temperature observation.

## Root Cause
Based on an empirical analysis of the `app_logs` database pulled from a Samsung Galaxy Z Fold 4 (`SM-F936U1`), the discrepancy is not a rounding error or an interpolation bug. It is a desynchronization caused by Samsung's OS-level alarm deferrals combined with per-widget user interactions.

1. **Samsung's Alarm Deferral**: The UI redraw uses `AlarmManager`'s `setAndAllowWhileIdle`. Samsung's aggressive battery management routinely delays and batches these alarms. Instead of firing every 2 minutes, logs show the `ui_update_alarm` firing 8 to 13 minutes apart.
2. **Independent User Interaction**: Interacting with one widget (e.g., toggling views) triggers an immediate refresh for *that specific widget only*. 
3. **The Discrepancy Window**: If the temperature curve is changing, the interacted widget will calculate a newer, minute-accurate interpolation. The other widget(s) will remain stuck displaying the older interpolation until Samsung allows the next global background alarm to fire, catching the rest of the widgets up.

## Example Timeline from Logs
- **11:56:08 (Global Update)**: Delayed `ui_update_alarm` fires. All widgets update. Widget 349 shows 63.75°.
- **11:59:14 (Independent Update)**: User interacts with Widget 346. It independently resolves a newer temperature of 63.95° based on the rising curve.
- **11:59 to 12:04 (Discrepancy)**: Widget 346 shows ~64.0° while Widget 349 shows ~63.8°.
- **12:04:13 (Re-synchronization)**: The OS allows the next global `ui_update_alarm` to fire. Both widgets sync to 64.08°.

## Proposed Solution
To fix this visual oddity, update the click handlers (e.g., in `WeatherWidgetProvider.kt`) so that tapping *any* widget broadcasts a global update intent. This will force all widgets on the home screen to snap to the exact same interpolation minute simultaneously.
