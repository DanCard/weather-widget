# Session Notes: In-Depth Investigation of Disappearing Precipitation Graph (March 25, 2026)

## 1. Problem Definition and User Context
**Reported Issue:** On Samsung devices (and occasionally on emulators), the precipitation (rain chance) graph appears initially but vanishes within a few seconds. The disappearance happens much faster on the Samsung hardware than on the emulator.
**Context:** The Weather Widget is a complex, home-screen-only UI that relies on custom `Canvas` drawing for its graphs. It uses a two-tier update system (UI interpolation vs. network fetches) and is highly sensitive to widget dimensions.

## 2. Technical Hypotheses
During the research phase, we identified three primary technical failure modes that could explain this "ghosting" behavior:

1.  **State Reset during Background Sync:**
    *   *Theory:* Shortly after the widget is first displayed, a background sync is often triggered if the data is deemed "stale." If `WeatherWidgetWorker.kt` calls `widgetStateManager.resetAllToggleStates()`, the widget might revert from the user's selected API (e.g., Open-Meteo) to the system default (NWS). 
    *   *Implication:* If the default source lacks hourly forecast data for that specific location, the graph renderer receives an empty data set and draws nothing.

2.  **Layout/Dimension Thrashing (Samsung-Specific):**
    *   *Theory:* Samsung's One UI launcher is known to report fluctuating widget dimensions (`onAppWidgetOptionsChanged`) during initialization and screen rotations.
    *   *Mechanism:* `PrecipViewHandler.kt` uses a threshold of `1.4` rows to decide between "Graph Mode" and "Text-only Mode." If the reported height briefly dips below this threshold, the code might automatically hide the graph view.

3.  **Data Fetch Failures or Cache Invalidation:**
    *   *Theory:* A refresh attempt (either UI-only or full network) might be providing an empty list of `HourlyForecastEntity` objects to the handler.
    *   *Mechanism:* If `fetchHourlyForecasts` in the worker fails to find data in the expected time window (24h past to 96h future), the handler is passed an empty list, resulting in a blank bitmap.

## 3. Implementation of Diagnostic Logging
To distinguish between these theories, we implemented a targeted logging strategy using the `PrecipDebug` tag. This "Evidence-First" approach avoids speculative fixes.

### Key Logging Points:
*   **`PrecipViewHandler.kt`**: Logged the `widgetId`, active `source`, `hourlyCount`, and the `useGraph` boolean. This tells us *if* the decision-making logic is flipping.
*   **`WidgetStateManager.kt`**: Logged inside `resetAllToggleStates()`. This confirms if a state reset is correlating with the disappearance.
*   **`PrecipitationGraphRenderer.kt`**: Logged when `renderGraph()` is called with an empty data set. This confirms if the data layer is the culprit.
*   **`WidgetIntentRouter.kt`**: Logged `handleResize()` events. This tracks if the launcher is forcing a layout change.
*   **`WeatherWidgetWorker.kt`**: Logged the completion status (SUCCESS/FAILURE) of background syncs to see if a specific fetch is clearing the cache.

## 4. Challenges Encountered & Resolutions

### Compilation Failure (Conflicting Declarations)
During the first implementation of the logs, I inadvertently introduced duplicate variable declarations for `rawRows` and `useGraph` in `PrecipViewHandler.kt`.
*   *Cause:* The `updateWidget` function had a large block of redundant code that I attempted to refactor and log simultaneously.
*   *Resolution:* I performed a surgical clean-up, removing the duplicated block and ensuring only one instance of the logic remained. This resolved the `Task :app:compileDebugKotlin FAILED` error.

### Log Persistence & Time Synchronization
*   *Issue:* Initial attempts to fetch logs via `adb logcat` were returning empty or outdated entries from before the new build was installed.
*   *Resolution:* 
    1.  Verified the device time (`adb shell date`) to ensure synchronization.
    2.  Performed a full `uninstall` of the app to clear any stale state.
    3.  Rebuilt and reinstalled the debug APK to guarantee the new logs were active.
    4.  Cleared the logcat buffer (`adb logcat -c`).

## 5. Current Investigation Strategy
The environment is now "clean" and ready for reproduction.
1.  **Reproduce:** Trigger the disappearance on the Samsung device.
2.  **Extract:** Run `adb -s RFCT71FR9NT logcat -s PrecipDebug` to capture the sequence of events.
3.  **Analyze:** Compare the timestamps of the disappearance to the logged resize events or state resets.

---
*Note: This session notes file was created in accordance with the "Teach and Learn" preference to provide detailed technical rationale for all debugging steps taken.*
