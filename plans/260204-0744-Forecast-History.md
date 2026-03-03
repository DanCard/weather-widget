# Forecast History Activity - Implementation Plan

## Goal
Tapping a day column in the **left half** of the widget opens a new Forecast History activity showing how forecasts evolved for that day. Right half taps continue opening Settings.

## Files to Modify
- `app/src/main/java/com/weatherwidget/data/local/ForecastSnapshotDao.kt` - new query
- `app/src/main/res/layout/widget_weather.xml` - graph overlay zones
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` - per-day click handlers
- `app/src/main/AndroidManifest.xml` - register new activity
- `app/src/main/res/values/strings.xml` - new strings

## New Files
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`
- `app/src/main/java/com/weatherwidget/ui/ForecastEvolutionAdapter.kt`
- `app/src/main/res/layout/activity_forecast_history.xml`
- `app/src/main/res/layout/item_forecast_evolution.xml`

---

## Step 1: Add DAO query

In `ForecastSnapshotDao.kt`, add `getForecastEvolution(targetDate, lat, lon)` returning all snapshots for a single target date across all forecast dates, ordered by `forecastDate ASC, source ASC`. This gives the full history of how predictions evolved.

## Step 2: Create activity layout

`activity_forecast_history.xml` - follows `activity_statistics.xml` pattern:
- Back button + "Forecast History" title + date subtitle
- Summary card showing actual temps (if past date) and snapshot count
- RecyclerView with forecast evolution items

## Step 3: Create list item layout

`item_forecast_evolution.xml` - each row shows:
- "Xd ahead" label (bold) + source badge (NWS / Open-Meteo)
- Forecast date + predicted high/low temps
- Error vs actual (color-coded green/yellow/red), only for past dates

## Step 4: Create adapter

`ForecastEvolutionAdapter.kt` - follows `DailyAccuracyAdapter` pattern. Simple RecyclerView.Adapter binding `ForecastEvolutionItem` data to the list item layout.

## Step 5: Create ForecastHistoryActivity

`ForecastHistoryActivity.kt` - Hilt `@AndroidEntryPoint`, receives intent extras:
- `EXTRA_TARGET_DATE` (String, ISO date)
- `EXTRA_LAT`, `EXTRA_LON` (Double)

On load:
1. Query `getForecastEvolution()` for all snapshots
2. Query actual weather for the date (if past)
3. Build list of `ForecastEvolutionItem` with daysAhead calculated as `ChronoUnit.DAYS.between(forecastDate, targetDate)`
4. Compute errors vs actual for past dates
5. Display summary + populate RecyclerView

Data class `ForecastEvolutionItem` defined inline or in a separate file under `stats/`.

## Step 6: Add graph overlay zones to widget layout

In `widget_weather.xml`, add a `LinearLayout` (`id: graph_day_zones`) with 6 transparent `FrameLayout` children (weighted equally), placed **after** `graph_view` but **before** `nav_left_zone` in the XML. This provides per-day tap targets over the bitmap graph.

- `marginStart=32dp`, `marginEnd=32dp` to avoid overlapping nav zones
- Default `visibility=gone`

## Step 7: Modify WeatherWidgetProvider click handling

In `updateWidgetWithData()`:

**Remove** the blanket `setOnClickPendingIntent` on `text_container` and `graph_view` that opens Settings (lines 667-668).

**Text mode** (numRows < 2): Set PendingIntents on each `dayN_container`:
- Calculate midpoint = `visibleDays.size / 2`
- Indices 0..<midpoint: PendingIntent to `ForecastHistoryActivity` with that day's date + lat/lon
- Indices midpoint..<size: PendingIntent to `SettingsActivity`

**Graph mode** (numRows >= 2): Show `graph_day_zones` overlay, hide unused zones, set PendingIntents:
- Same left/right split logic as text mode
- Left half zones -> ForecastHistoryActivity
- Right half zones -> SettingsActivity

**Hourly mode**: Hide `graph_day_zones`, keep graph_view click -> Settings.

**Lat/lon**: Extract from `weatherList.firstOrNull()` since it's already available in the weather data.

**PendingIntent request codes**: Use `appWidgetId * 100 + dayIndex` (text mode) and `appWidgetId * 100 + 50 + dayIndex` (graph mode) for uniqueness.

## Step 8: Register activity in AndroidManifest

Add `ForecastHistoryActivity` after `StatisticsActivity`.

## Step 9: Add string resources

Add `forecast_history` string.

---

## Verification

1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Test text mode (1-row widget): tap left-half day -> forecast history opens, tap right-half day -> settings opens
3. Test graph mode (2+ row widget): same left/right behavior
4. Test forecast history activity: shows date, actual temps (for past dates), list of forecasts ordered by days-ahead
5. Test with various widget sizes (3, 4, 5 columns)
6. Verify nav arrows, API toggle, and current temp toggle still work
7. Verify hourly mode is unaffected
