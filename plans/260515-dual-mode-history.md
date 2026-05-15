# Daily Forecast View — Three-Bar History in Dual API Mode

## Context

When a widget is in dual-API mode (both NWS and Open-Meteo bars shown side-by-side), the user expects historical days to display **three bars**:

1. **Blended actuals** (red, center) — the observed weather
2. **NWS forecast snapshot** — what NWS predicted for that day
3. **Open-Meteo forecast snapshot** — what Open-Meteo predicted for that day

Today only #1 renders. The two forecast bars are missing because:

- The forecast snapshot query (`activeSourceList` in `WeatherWidgetProvider.kt`) only includes each widget's **current** display source, so the **second** source's snapshot rows are never loaded from the DB.
- In `DailyViewLogic.kt`, `nextSourceHigh/Low` is sourced from `nextSourceWeatherByDate` (the `weatherList`, i.e. current forecasts/actuals). For past dates this map either holds the actuals or nothing — never the historical *forecast snapshot* the user wants.
- In `DailyForecastGraphRenderer.kt:712`, the yellow forecast overlay is intentionally suppressed when `nextSourceHigh/Low` is set, because the next-source bar was designed to *reuse* the overlay's slot.

## Approach

Three-part fix across the fetch, transform, and render layers:

### 1. Fetch both sources' snapshots when dual mode is on

**File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` (around lines 158–162)

When building `activeSources`, also include each widget's *next* display source when `stateManager.isShowTwoBarsEnabled()` is true:

```kotlin
val activeSources = filteredIds
    .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
    .flatMap { widgetId ->
        val primary = stateManager.getCurrentDisplaySource(widgetId).id
        if (stateManager.isShowTwoBarsEnabled()) {
            val next = stateManager.getNextDisplaySource(widgetId).id
            listOf(primary, next)
        } else {
            listOf(primary)
        }
    }
    .toSet() + WeatherSource.GENERIC_GAP.id
```

This makes the snapshot rows for the second source available in the `forecastSnapshots` map that `DailyViewLogic` consumes.

### 2. Populate `nextSourceHigh/Low` from snapshots on past days

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (lines 365–386 and 584–585)

In the `if (isPastDate)` branch, in addition to the existing `pastForecast` lookup for `displaySource`, compute a parallel `pastNextSourceForecast` for `nextSource` from the same `forecasts` list (the snapshot data — *not* `nextSourceWeatherByDate`):

```kotlin
if (isPastDate) {
    finalHigh = actual?.highTemp
    finalLow = actual?.lowTemp

    if (showComparison) {
        val pastForecast = forecasts
            .filter { it.source == displaySource.id }
            .filter { !it.isClimateNormal }
            .filter { it.highTemp != null && it.lowTemp != null }
            .maxByOrNull { it.fetchedAt }
        fHigh = pastForecast?.highTemp
        fLow = pastForecast?.lowTemp

        // NEW: capture the OTHER source's snapshot for the third bar
        if (nextSource != null && nextSource != displaySource) {
            val pastNextSourceForecast = forecasts
                .filter { it.source == nextSource.id }
                .filter { !it.isClimateNormal }
                .filter { it.highTemp != null && it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }
            pastNextSourceHigh = pastNextSourceForecast?.highTemp
            pastNextSourceLow  = pastNextSourceForecast?.lowTemp
        }
    }
}
```

Then at the DayData construction (lines 584–585), prefer the past-snapshot values for past days:

```kotlin
nextSourceHigh = if (isPastDate) pastNextSourceHigh else nextSourceWeather?.highTemp,
nextSourceLow  = if (isPastDate) pastNextSourceLow  else nextSourceWeather?.lowTemp,
```

### 3. Render all three bars on past days in dual mode

**File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` (lines 710–749)

Currently `suppressForecastOverlay` blocks the yellow overlay whenever a next-source bar exists. For past days in dual mode we want **both** to draw. Change the condition so suppression applies only to non-past days:

```kotlin
val suppressForecastOverlay =
    !day.isPast && day.nextSourceHigh != null && day.nextSourceLow != null
```

Both the overlay (at `centerX + forecastBarOffset`) and the next-source bar (at `centerX + nextSourceBarOffset`) currently sit ~0.7×barWidth to the right of center, so they'd overlap visually. To give all three bars distinct positions for past days, mirror the next-source bar to the **left** of the primary bar on past days only:

```kotlin
val nextX = centerX + when {
    day.isToday -> layout.tripleBarOffset
    day.isPast  -> -layout.nextSourceBarOffset   // mirror to the LEFT
    else        ->  layout.nextSourceBarOffset
}
```

Result on past days: `[NextSourceBar] [RedActualsBar] [YellowForecastOverlay]`.

## Files to Modify

- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` — include next source in `activeSources` when dual mode is on
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — extract `pastNextSourceForecast` from `forecasts` in the `isPastDate` branch; route it to `DayData.nextSourceHigh/Low`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — lift `suppressForecastOverlay` on past days; mirror `nextSourceBarOffset` to the left for past days

## Reused Functions / Patterns

- `ForecastDao.getLatestForecastsInRangeForSources` / `getAllForecastsInRangeForSources` — already query by a source list; no DAO change needed once `activeSourceList` is extended
- The existing `pastForecast` filter chain in `DailyViewLogic.kt:375-379` is reused verbatim for the next-source lookup (just swapping `displaySource.id` → `nextSource.id`)
- `drawNextSourceBar()` at `DailyForecastGraphRenderer.kt:777` is unchanged; only the call site's `nextX` calculation changes

## Verification

1. **Build & install:** `./gradlew installDebug`
2. **Reproduce the original bug** (sanity check before swap):
   - Enable dual-bar toggle on a widget (tap the API indicator in daily view).
   - Navigate to a past day (left arrow).
   - Confirm only the red actuals bar is present (no forecast bars).
3. **Apply fix and verify:**
   - Same steps as above, now confirm three distinct bars appear on past days: red actuals (center), yellow NWS forecast (right), Open-Meteo bar (left).
   - Toggle dual-bar off → confirm we fall back to red actuals + single yellow forecast overlay (existing behavior preserved).
   - Navigate to today → confirm existing dual-mode triple-bar rendering for today is unaffected.
   - Navigate to future days → confirm dual-mode two-bar rendering is unaffected.
4. **DB sanity check:** `python3 scripts/backup_databases.py` then `sqlite3` to confirm `ForecastSnapshotEntity` rows exist for both `nws` and `meteo` sources for the recent past dates being tested. If they don't, the user hasn't accumulated enough dual-source history for the bars to appear — note this in test results.
5. **Log validation:** Watch `adb logcat | grep "prepareGraphDays\|Bar color decision"` and verify the next-source-bar log line fires for past dates after the fix.
