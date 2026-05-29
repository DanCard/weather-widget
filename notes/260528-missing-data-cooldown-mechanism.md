# Missing Data Cooldown Mechanism

## Purpose

Prevent redundant network fetches when the widget renders and discovers missing data (e.g., today's actual temperature, historical observations, or a forecast snapshot). Without cooldown, every widget repaint would trigger a new fetch.

## Flow

1. **`MissingDataRefreshHelper.kt`** defines the decisions. `computeMissingDataRefreshes()` inspects the current data state and returns a list of `MissingDataRefreshDecision` objects — each representing a gap (missing actuals, missing snapshot, missing history). The cooldown is a constant: **5 minutes** (`MISSING_DATA_REFRESH_COOLDOWN_MS`).

2. **`DailyViewHandler.kt`** calls `requestMissingDataRefresh()` for each decision, passing `decision.cooldownMs`. That function gates the fetch:
   - Calls `stateManager.shouldRefreshMissingData()` — if the last fetch for this widget+source+type was **within** the cooldown window, it returns `false` and the request is silently skipped.
   - If the cooldown has expired, it calls `markMissingDataRefreshRequested()` to record the current timestamp, then triggers `WeatherWidgetProvider.triggerImmediateUpdate()`.

3. **`WidgetStateManager.kt`** stores timestamps in `SharedPreferences` keyed by `widgetId_sourceId_refreshType`. The check is simply:
   ```
   System.currentTimeMillis() - lastRequested >= cooldownMs
   ```

4. **`HourlyObservationBackfill.kt`** uses the same pattern but logs a skip message when cooldown blocks the refresh.

## Summary

The cooldown is a 5-minute debounce per widget per data source per refresh type. It ensures that if the widget repaints multiple times in quick succession (e.g., screen rotation, resize), it won't spam the API with duplicate fetch requests for the same missing data.
