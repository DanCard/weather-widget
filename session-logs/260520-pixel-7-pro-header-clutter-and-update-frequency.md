# 2026-05-20 — Pixel 7 Pro Widget Header Fix and Update Frequency Analysis

## Summary

In this session, we investigated and resolved a visual bug affecting the home screen widget's **Daily view mode** on the physical Google Pixel 7 Pro (`2A191FDH300PPW`). The widget header was cluttered with floating and inline hourly specific navigation and context icons (home shortcut, weather stations, and history graph touch targets). Additionally, we analyzed and documented the widget's power-aware update cadence for both UI temperature interpolation and active network observations from the weather APIs, concluding with an adb-driven diagnostic query of the Pixel's current runtime state.

---

## User Prompts

1. `on attached pixel 7 pro, the header line is messed up. Includes icons from hourly graph, which shouldn't be there.`
2. `Changing subject: How often should I expect attached pixel 7 pro to update current temperature observations?`
3. `How often should I expect attached pixel 7 pro to update current temperature observations from api?`
4. `Query the pixel, what state is it in? How often should I expect attached pixel 7 pro to update current temperature observations from api in current state?`
5. `Write above answers and session log to session-logs/ dir`

---

## Widget Header Fix (Pixel 7 Pro Layout)

### Root Cause
Because Android `RemoteViews` updates operate as incremental delta applications, layout changes made in one view binder persist across mode transitions unless explicitly reset. When switching the widget from an Hourly view (which displays inline/floating navigation elements under narrow columns) back to a Daily view, the visibilities of the hourly-specific frame layouts (`home_touch_zone_inline`, `forecast_history_activity_touch_zone_inline`, and `weather_stations_touch_zone_inline`) persisted in the top-left current weather container, resulting in a cluttered daily header.

### Fix Implementation
1. **[DailyVisibilityManager.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyVisibilityManager.kt)**:
   - Added explicit visibility suppression (`View.GONE`) inside both `setGraphModeViews()` and `setTextModeViews()` for all floating and inline hourly specific controls/icons.
2. **[DailyViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt)**:
   - Added explicit `View.GONE` mapping for the three newly introduced inline hourly touch targets inside the daily handler `updateWidget()` flow, ensuring complete protection against layout state leakage.

### Verification
- **Build and Deploy**: Succeeded with `./gradlew installDebug` deploying to the physical Google Pixel 7 Pro.
- **Visual Capture**: A post-fix screenshot was pulled via adb (`screencap`) showing a perfectly clean header containing only the current temperature "72°" on the left and the active "NWS" API toggle + settings gear on the right.
- **Tests**: Ran `./gradlew test` with all 38 tasks passing (no regressions).

---

## Temperature Update Frequencies (Pixel 7 Pro)

We analyzed the codebase to define the two independent layers of temperature updates on the Pixel 7 Pro:

### 1. Widget UI & Current Temp Display (Interpolated from Cache)
To present a smooth, battery-friendly visual transition, the widget interpolates temperature changes minute-by-minute between hourly forecasts.

| Device State | Screen Activity | Update Interval | Wakeup Type |
| :--- | :--- | :--- | :--- |
| **Charging (Plugged In)** | Screen On (Interactive) | **Every 2 minutes** | Immediate updates + schedules next at 2-min limit |
| **Charging (Plugged In)** | Screen Off | Keep-fresh loop active | Prepares fresh temperature values for screen-on |
| **On Battery** | Screen Unlock (`ACTION_USER_PRESENT`) | **Instant** | Triggers an immediate, fresh UI draw on unlock |
| **On Battery** | Screen Off (Idle) | **Dynamic: 15 to 60 mins** | **Opportunistic** (does not wake the CPU; piggybacks on other app activity to save power) |

* **Dynamic Off-Charger UI Cadence**: When on battery and the device is idle, the background UI update interval dynamically adapts based on the rate of temperature change predicted in the hourly forecasts:
  * **Rapid Change** ($\ge 6^\circ$ change between hours): Updates every **15 minutes**.
  * **Moderate Change** ($4^\circ\text{ to } 5^\circ$ change between hours): Updates every **20 minutes**.
  * **Mild Change** ($2^\circ\text{ to } 3^\circ$ change between hours): Updates every **30 minutes**.
  * **Flat Temperature** ($< 2^\circ$ change between hours): Updates every **60 minutes**.

---

### 2. Network Forecast Data Fetches (API Observations Updates)
Actual network requests to pull fresh observations and forecast files from the weather APIs are managed by battery-aware background tasks.

* **When Plugged In (Charging)**:
  * **Screen On**: Active source fetches every **60 minutes**; non-active sources every **120 minutes**.
  * **Screen Off**: Active source fetches every **120 minutes**; non-active sources every **240 minutes**.
  * **Connection Triggers**: Connecting power or unlocking screen while plugged in triggers immediate, out-of-band current-temp API updates.
* **When On Battery (Off Charger)**:
  * **High Battery (> 70%)**: Fetches every **240 minutes** (4 hours).
  * **Medium Battery (51% – 70%)**: Fetches every **480 minutes** (8 hours).
  * **Low Battery ($\le$ 50%)**: Suspends background API syncs entirely to prevent battery drain (manual fetches are still allowed down to 30% battery).

---

## Attached Pixel 7 Pro State Query Results

We queried the attached Pixel 7 Pro (`2A191FDH300PPW`) at `2026-05-20T08:10:03-07:00` using `adb dumpsys battery` and `adb dumpsys power` to analyze its live status:

1. **Power Status**: **Charging / Plugged In** (`AC powered: true`, `status: 4`)
2. **Screen Activity**: **Screen Interactive / On** (`mIsInteractive: true`)
3. **Battery Level**: **80%**

### Current Expected Behavior in this State
In this exact state (Charging + Screen Interactive), the Pixel 7 Pro is configured to:
* Fetch new observations from the weather API **every 10 minutes** (`CHARGING_INTERVAL_MINUTES`).
* Perform lightweight UI-only updates from the interpolated cache **every 2 minutes** (`PLUGGED_IN_MAX_DELAY_MS`).
