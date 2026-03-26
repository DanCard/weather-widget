# Fix: Precipitation graph goes blank on tap from daily view

## Context

When tapping the rain % indicator from the daily view on both Pixel and Samsung, the widget transitions to the precipitation graph mode but shows only header elements (temp, rain %, API source) — the main graph area is blank/transparent. This is reproducible across devices, indicating a code bug rather than a device-specific issue.

**Flow**: Tap rain % → `ACTION_TOGGLE_PRECIP` → `handleTogglePrecip()` → `togglePrecipitationMode()` (resets offset=0, zoom=WIDE) → `refreshGraphView()` → `loadGraphWindowHourlyForecasts()` → `PrecipViewHandler.updateWidget()` → `buildPrecipHourDataList()` → `PrecipitationGraphRenderer.renderGraph()`

## Root cause hypothesis

The header rain % can show correctly even when hourly data is empty because `HeaderPrecipCalculator` falls back to `fallbackDailyProbability` (from `ForecastEntity`). The current temp can also show via observation data fallbacks. So "header visible, graph empty" means **the hourly forecast data for the graph is empty or not matching**.

Two possible sub-causes:
1. **Empty hourly data from DB** — `loadGraphWindowHourlyForecasts` returns 0 rows (location mismatch, data not fetched, or cleanup)
2. **Epoch-millis key mismatch** — hourly data exists but `buildPrecipHourDataList` exact-key lookup `forecastsByTime[hourMs]` misses because stored `dateTime` values don't exactly match generated hour keys

## Step 1: Diagnose with device logs and DB

Pull logs and database from the Pixel to check actual state:

```bash
# Pull recent logs
adb logcat -d -s PrecipViewHandler:* WidgetIntentRouter:* WeatherWidgetProvider:* | tail -100

# Pull and query database
python3 scripts/backup_databases.py
sqlite3 <db_path> "SELECT COUNT(*), MIN(dateTime), MAX(dateTime) FROM hourly_forecasts;"
sqlite3 <db_path> "SELECT dateTime, source, precipProbability FROM hourly_forecasts ORDER BY dateTime DESC LIMIT 20;"
```

## Step 2: Add diagnostic logging

**File: `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`**

In `buildPrecipHourDataList()` (~line 553), add logging to track:
- Input: `hourlyForecasts.size` and `forecastsByTime.size`
- The time window: `startHour` to `endHour`
- Output: `hours.size`
- If hours is empty but hourlyForecasts is not: log the first few forecastsByTime keys vs generated hourMs to detect mismatch

In `updateWidget()` (~line 235), log when `hours.isEmpty()` after `buildPrecipHourDataList` returns.

## Step 3: Fix the root cause

### If cause is epoch-millis key mismatch:
Replace exact map lookup with nearest-hour lookup in `buildPrecipHourDataList`:

```kotlin
// Instead of:
val forecast = forecastsByTime[hourMs]

// Use nearest-match within ±30min tolerance:
val forecast = forecastsByTime.entries
    .filter { abs(it.key - hourMs) <= 30 * 60 * 1000L }
    .minByOrNull { abs(it.key - hourMs) }
    ?.value
```

**Note**: This same pattern should be applied to `TemperatureViewHandler.buildHourDataList()` and `CloudCoverViewHandler` (if similar) for consistency — they use the same exact-key lookup pattern.

### If cause is empty hourly data from DB:
Add a guard that shows a meaningful fallback instead of a blank bitmap. When `hours.isEmpty()` and `hourlyForecasts.isEmpty()`:
- Log the query parameters (lat, lon, time window)
- Show a text-mode fallback or "No hourly data available" message instead of an empty graph

## Step 4: Add empty-graph fallback (regardless of root cause)

In `PrecipViewHandler.updateWidget()`, after `buildPrecipHourDataList()` returns empty:

```kotlin
if (hours.isEmpty()) {
    Log.w(TAG, "buildPrecipHourDataList returned empty! hourlyForecasts=${hourlyForecasts.size}, " +
        "forecastsByTime keys=${forecastsByTime.keys.take(3)}, " +
        "window=$startHour..$endHour, generated hourMs samples=...")
    // Fall back to text mode or show "No data" instead of blank bitmap
}
```

## Files to modify

| File | Change |
|------|--------|
| `app/.../handlers/PrecipViewHandler.kt` | Add diagnostic logging in `buildPrecipHourDataList` and `updateWidget`; add empty-hours fallback |
| `app/.../handlers/TemperatureViewHandler.kt` | Apply same fix to `buildHourDataList` if key-mismatch is confirmed |
| `app/.../handlers/CloudCoverViewHandler.kt` | Apply same fix if it uses the same pattern |

## Verification

1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Add widget to home screen, wait for data to load
3. From daily view, tap rain % to enter precipitation mode
4. Verify the graph renders (not blank)
5. Check logs: `adb logcat -s PrecipViewHandler:*` to confirm data flow
6. Test on emulator: `./scripts/emulator-tests.sh` (if relevant tests exist)
7. Test zoom cycling: tap graph to zoom in, verify narrow view also renders
