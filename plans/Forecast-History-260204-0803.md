# Forecast History Activity - Implementation Plan

## Goal
1. Save forecast snapshots for ALL future days on every API fetch (not just 1-day-ahead for tomorrow)
2. Save every fetch (not just one per day) by adding `fetchedAt` to the primary key
3. New activity showing forecast evolution when tapping a day in the widget's left half
4. Right-half day taps continue opening Settings

---

## Part A: Schema & Data Collection Changes

### A1. Database migration (v11 -> v12)
**File:** `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`

Add `MIGRATION_11_12`: Recreate `forecast_snapshots` table with `fetchedAt` added to the primary key:
```
PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
```
This allows multiple snapshots per (targetDate, forecastDate, source) - one per fetch. Bump version to 12.

### A2. Update ForecastSnapshotEntity
**File:** `app/src/main/java/com/weatherwidget/data/local/ForecastSnapshotEntity.kt`

Add `fetchedAt` to the `primaryKeys` array in the `@Entity` annotation.

### A3. Expand saveForecastSnapshot to save all future days
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`

Modify `saveForecastSnapshot()`:
- Instead of only saving tomorrow's forecast, iterate through ALL weather entries where `date > today`
- Remove `shouldSaveSnapshot()` dedup logic (no longer needed since every fetch is unique via `fetchedAt`)
- Each future day creates a snapshot with `targetDate=futureDay, forecastDate=today`

### A4. Update AccuracyCalculator for multiple snapshots per day
**File:** `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`

In `getDailyAccuracyBreakdown()` line 133: change `forecasts.find { ... }` to `forecasts.filter { ... }.maxByOrNull { it.fetchedAt }` so it uses the latest forecast for each (targetDate, forecastDate, source) combination.

### A5. Update DAO queries
**File:** `app/src/main/java/com/weatherwidget/data/local/ForecastSnapshotDao.kt`

- `getForecastForDate`: Add `fetchedAt DESC` to ORDER BY (already has LIMIT 1)
- `getSpecificForecast`: Add `ORDER BY fetchedAt DESC LIMIT 1` so it returns the latest
- `getForecastForDateBySource`: Add `ORDER BY fetchedAt DESC LIMIT 1`
- Add new `getForecastEvolution(targetDate, lat, lon)`: Returns ALL snapshots for a target date, ordered by `forecastDate ASC, fetchedAt ASC`

---

## Part B: Forecast History Activity

### B1. Create activity layout
**New file:** `app/src/main/res/layout/activity_forecast_history.xml`

Follows `activity_statistics.xml` pattern:
- Back button + "Forecast History" title + date subtitle (e.g., "Wed, Feb 5")
- Summary card: actual temps (if past date), snapshot count
- RecyclerView with forecast evolution items

### B2. Create list item layout
**New file:** `app/src/main/res/layout/item_forecast_evolution.xml`

Each row shows:
- "Xd ahead" label + source badge (NWS / Open-Meteo)
- Forecast date + time + predicted high/low
- Error vs actual (color-coded green/yellow/red) for past dates only

### B3. Create ForecastEvolutionAdapter
**New file:** `app/src/main/java/com/weatherwidget/ui/ForecastEvolutionAdapter.kt`

Follows `DailyAccuracyAdapter` pattern. Binds evolution items to list rows.

### B4. Create ForecastHistoryActivity
**New file:** `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`

- `@AndroidEntryPoint`, receives `EXTRA_TARGET_DATE`, `EXTRA_LAT`, `EXTRA_LON`
- Queries `getForecastEvolution()` for all snapshots
- Queries actual weather if past date
- Builds list with daysAhead = `ChronoUnit.DAYS.between(forecastDate, targetDate)`
- Shows errors for past dates

### B5. Register in manifest + strings
**Files:**
- `app/src/main/AndroidManifest.xml` - add activity
- `app/src/main/res/values/strings.xml` - add "forecast_history" string

---

## Part C: Widget Per-Day Click Handling

### C1. Add graph overlay zones to widget layout
**File:** `app/src/main/res/layout/widget_weather.xml`

Add a `LinearLayout` (`id: graph_day_zones`) with 6 transparent weighted `FrameLayout` children, placed after `graph_view` but before nav zones. Margins match nav zone widths (32dp each side). Default `visibility=gone`.

### C2. Modify WeatherWidgetProvider click handlers
**File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`

**Remove** blanket `setOnClickPendingIntent` on `text_container` and `graph_view` for Settings (lines 667-668).

**Text mode**: Set PendingIntents on individual `dayN_container` views:
- midpoint = visibleDays / 2
- Left-half containers (index < midpoint) -> ForecastHistoryActivity with date + lat/lon
- Right-half containers (index >= midpoint) -> SettingsActivity

**Graph mode**: Show `graph_day_zones`, set per-zone PendingIntents:
- Same left/right split
- Hide unused zones (GONE)

**Hourly mode**: Hide `graph_day_zones`, keep settings click on graph_view.

PendingIntent request codes: `appWidgetId * 100 + dayIndex` (text) and `appWidgetId * 100 + 50 + dayIndex` (graph).

---

## Files Summary

### Modified
| File | Change |
|------|--------|
| `WeatherDatabase.kt` | Add MIGRATION_11_12, bump version to 12 |
| `ForecastSnapshotEntity.kt` | Add fetchedAt to primaryKeys |
| `ForecastSnapshotDao.kt` | Update queries, add getForecastEvolution |
| `WeatherRepository.kt` | Expand saveForecastSnapshot, remove shouldSaveSnapshot |
| `AccuracyCalculator.kt` | Use maxByOrNull for latest forecast |
| `widget_weather.xml` | Add graph_day_zones overlay |
| `WeatherWidgetProvider.kt` | Per-day click handlers |
| `AndroidManifest.xml` | Register ForecastHistoryActivity |
| `strings.xml` | Add forecast_history string |

### New
| File | Purpose |
|------|---------|
| `activity_forecast_history.xml` | Activity layout |
| `item_forecast_evolution.xml` | List item layout |
| `ForecastHistoryActivity.kt` | Activity showing forecast evolution |
| `ForecastEvolutionAdapter.kt` | RecyclerView adapter |

---

## Verification

1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Verify migration: app should not crash on upgrade (existing data preserved)
3. Force a data fetch, then check DB for multi-day forecast snapshots
4. Test text mode widget: left-half days -> forecast history, right-half -> settings
5. Test graph mode widget: same left/right split
6. Test forecast history activity: shows date, actual vs predicted, evolution list
7. Test various widget sizes (3, 4, 5 columns)
8. Verify nav arrows, API toggle, current temp toggle still work
9. Verify hourly mode unaffected
10. Verify StatisticsActivity accuracy calculations still correct
