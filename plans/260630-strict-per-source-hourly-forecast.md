# Plan: Enforce Strict Per-Source Filtering for Hourly Temperature Forecasts

## Context
When viewing the hourly temperature graph for a specific API source (e.g. NWS) near or past its forecast cutoff (e.g., Monday, July 6 at 22:00 local time), the graph continues to display temperature data. This happens because the query for hourly forecast data in `refreshGraphView` loads unfiltered data from all sources, and the temperature smoothing pipeline in `CurrentTemperatureResolver.pickBestForecast` falls back to alternative sources (like Open-Meteo or Silurian) when the display source is missing.

The user expects that for each API, **only that API data source should be shown**, and there should be no cross-source fallbacks.

## Goal
Enforce strict per-source filtering for hourly forecasts in the hourly graph and current temperature resolver, ensuring that a selected source's hourly graph ends at its actual cutoff.

---

## Recommended Approach

1. **Pass Display Source when Refreshing Graphs**:
   Modify `WidgetIntentRouter.refreshGraphView` to pass the selected `displaySource` to `GraphDataLoader.loadGraphWindowHourlyForecasts`. This restricts the database query to only the selected source's hourly rows.

2. **Filter Background Worker Render Inputs**:
   Modify `WidgetRenderer.updateWidgetWithData` (which renders widgets on background updates/resizes/alarms) to filter the `unifiedHourlyForecasts` list by `displaySource.id` before passing it to individual view mode handlers (`TemperatureViewHandler`, `PrecipViewHandler`, `CloudCoverViewHandler`).

3. **Disable Cross-Source Fallbacks in pickBestForecast**:
   In `CurrentTemperatureResolver.pickBestForecast` (which resides in the `:shared` module and is shared with Compose Desktop), remove the `else -> rows` fallback block. If the display source (and optionally `GENERIC_GAP`) is absent, return an empty candidate list so that it resolves to `null`/`Float.NaN` for those hours.

---

## Critical Files to Modify

* **`app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`**
  * Path: [WidgetIntentRouter.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt)
  * Pass the `displaySource` to `GraphDataLoader.loadGraphWindowHourlyForecasts`.

* **`app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`**
  * Path: [WidgetRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt)
  * Filter `unifiedHourlyForecasts` by `displaySource.id` before dispatching to hourly view handlers.

* **`shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt`**
  * Path: [CurrentTemperatureResolver.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt)
  * Remove `else -> rows` inside `pickBestForecast` so that it returns `null`/`emptyList()` if neither the display source nor `GENERIC_GAP` is present.

---

## Existing Functions / Utilities to Reuse

* [GraphDataLoader.loadGraphWindowHourlyForecasts](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/GraphDataLoader.kt#L45) already takes an optional `source: WeatherSource?` parameter.
* [CurrentTemperatureResolver.pickBestForecast](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt#L76) coordinates selection of the winning forecast per hour.

---

## Implementation Outline (Concise Steps)

### Step 1: Update WidgetIntentRouter.kt
In `WidgetIntentRouter.refreshGraphView(...)` (line 866), pass the `displaySource` to the `loadGraphWindowHourlyForecasts` call:
```kotlin
        val hourlyForecasts =
            GraphDataLoader.loadGraphWindowHourlyForecasts(
                hourlyDao = database.hourlyForecastDao(),
                hourlyHistoryDao = database.hourlyForecastHistoryDao(),
                lat = lat,
                lon = lon,
                centerTime = centerTime,
                zoom = zoom,
                now = now,
                source = displaySource, // Enforce source query limit
            )
```

### Step 2: Update WidgetRenderer.kt
In `WidgetRenderer.updateWidgetWithData(...)` (line 248), filter the hourly list passed to the hourly view handlers:
```kotlin
        val sourceFilteredHourlyForecasts = unifiedHourlyForecasts.filter { 
            it.source == displaySource.id || it.source == com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id 
        }
```
Pass `sourceFilteredHourlyForecasts` as the `hourlyForecasts` parameter in the `ViewMode` handlers.

### Step 3: Update CurrentTemperatureResolver.kt
Modify `pickBestForecast` to eliminate the fallback to all rows:
```kotlin
    private fun pickBestForecast(
        rows: List<HourlyForecast>,
        sourceId: String,
    ): HourlyForecast? {
        val candidates = when {
            rows.any { it.source == sourceId } -> rows.filter { it.source == sourceId }
            rows.any { it.source == WeatherSource.GENERIC_GAP.id } -> rows.filter { it.source == WeatherSource.GENERIC_GAP.id }
            else -> emptyList() // Do not fall back to other available sources
        }
        return candidates.maxByOrNull { it.fetchedAt }
    }
```

### Step 4: Verification & Testing
1. Run local JVM tests to verify if any existing test assertions are broken by the stricter filtering:
   ```bash
   ./gradlew test
   ```
2. Build and run instrumented tests:
   ```bash
   ./scripts/emulator-tests.sh
   ```
3. Take a screenshot or check logs on the emulator to verify that the NWS temperature graph stops at Monday July 6 cutoff and doesn't show Open-Meteo data past it.
