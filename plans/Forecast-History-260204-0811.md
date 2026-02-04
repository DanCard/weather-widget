# Forecast History Activity - Implementation Plan

## Goal
1. Save forecast snapshots for ALL future days on every API fetch (not just 1-day-ahead for tomorrow)
2. Save every fetch (not just one per day) by adding `fetchedAt` to the primary key
3. New activity showing forecast evolution as graphs when tapping a day in the widget's left half
4. Right-half day taps continue opening Settings

---

## Part A: Schema & Data Collection Changes

### A1. Database migration (v11 -> v12)
**File:** `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`

Add `MIGRATION_11_12`: Recreate `forecast_snapshots` table with `fetchedAt` in the primary key:
```
PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
```
Bump version to 12.

### A2. Update ForecastSnapshotEntity
**File:** `app/src/main/java/com/weatherwidget/data/local/ForecastSnapshotEntity.kt`

Add `fetchedAt` to the `primaryKeys` array.

### A3. Expand saveForecastSnapshot to save all future days
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`

Modify `saveForecastSnapshot()`:
- Iterate through ALL weather entries where `date > today` (not just tomorrow)
- Remove `shouldSaveSnapshot()` dedup logic (every fetch is unique via fetchedAt)
- Each future day creates a snapshot: `targetDate=futureDay, forecastDate=today`

### A4. Update AccuracyCalculator
**File:** `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`

Line 133: Change `forecasts.find { ... }` to `forecasts.filter { ... }.maxByOrNull { it.fetchedAt }` to use the latest forecast when multiple exist per (targetDate, forecastDate, source).

### A5. Update DAO queries
**File:** `app/src/main/java/com/weatherwidget/data/local/ForecastSnapshotDao.kt`

- `getForecastForDate`: Add `fetchedAt DESC` to ORDER BY
- `getSpecificForecast`: Add `ORDER BY fetchedAt DESC LIMIT 1`
- `getForecastForDateBySource`: Add `ORDER BY fetchedAt DESC LIMIT 1`
- Add new `getForecastEvolution(targetDate, lat, lon)`: Returns ALL snapshots for a target date, ordered by `forecastDate ASC, fetchedAt ASC`

---

## Part B: Forecast History Activity (Graphical)

### B1. Create ForecastEvolutionRenderer
**New file:** `app/src/main/java/com/weatherwidget/widget/ForecastEvolutionRenderer.kt`

Renders two graphs to a Bitmap, following `HourlyGraphRenderer` visual style:

**Top half - High Temperature Evolution:**
- X-axis: forecast dates (labeled "7d", "6d", "5d"... "1d")
- Y-axis: temperature range
- Blue (#5AC8FA) bezier curve for NWS high temp predictions over time
- Green (#34C759) bezier curve for Open-Meteo high temp predictions
- Orange (#FF9F0A) horizontal dashed line for actual high temp (past dates only)
- "Actual: 72" label on the actual line
- Temperature labels at key points on curves

**Bottom half - Low Temperature Evolution:**
- Same layout but for low temps
- Same color scheme
- Orange dashed line for actual low

**Multiple fetches per day:** When there are multiple snapshots for the same forecastDate (e.g., 3 fetches on Tuesday for Friday), show each point on the curve (creating a denser section of the curve for that day). X-axis positions are spaced by forecastDate with sub-positions for multiple fetches within a day.

**Data class:**
```kotlin
data class EvolutionPoint(
    val forecastDate: String,      // When forecast was made
    val fetchedAt: Long,           // Exact fetch time
    val daysAhead: Int,
    val highTemp: Int?,
    val lowTemp: Int?,
    val source: String             // "NWS" or "OPEN_METEO"
)
```

### B2. Create activity layout
**New file:** `app/src/main/res/layout/activity_forecast_history.xml`

```
LinearLayout (vertical, dark background)
  ├── Header: back button + "Forecast History" + date subtitle
  ├── Summary card: actual temps (if past) + snapshot count + sources
  ├── "High Temperature" label
  ├── ImageView (high temp graph, weight=1)
  ├── "Low Temperature" label
  └── ImageView (low temp graph, weight=1)
```

Both ImageViews get bitmaps from `ForecastEvolutionRenderer`.

### B3. Create ForecastHistoryActivity
**New file:** `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`

- `@AndroidEntryPoint`, receives `EXTRA_TARGET_DATE`, `EXTRA_LAT`, `EXTRA_LON`
- Queries `getForecastEvolution()` for all snapshots
- Queries actual weather for past dates
- Groups snapshots by source
- Renders two graphs via `ForecastEvolutionRenderer` (high and low)
- Summary text shows: "Actual: 72/55" + "14 forecasts from NWS, 12 from Open-Meteo"

### B4. Register in manifest + strings
**Files:**
- `app/src/main/AndroidManifest.xml` - add ForecastHistoryActivity
- `app/src/main/res/values/strings.xml` - add "forecast_history" string

---

## Part C: Widget Per-Day Click Handling

### C1. Add graph overlay zones to widget layout
**File:** `app/src/main/res/layout/widget_weather.xml`

Add `LinearLayout` (`id: graph_day_zones`) with 6 transparent weighted `FrameLayout` children, placed after `graph_view` but before nav zones. `marginStart/End=32dp` to avoid nav overlap. Default `visibility=gone`.

### C2. Modify WeatherWidgetProvider click handlers
**File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`

**Remove** blanket settings click on `text_container` and `graph_view` (lines 667-668).

**Text mode**: Per-container PendingIntents on `dayN_container`:
- midpoint = visibleDays / 2
- Left half (index < midpoint) -> ForecastHistoryActivity with date + lat/lon
- Right half (index >= midpoint) -> SettingsActivity

**Graph mode**: Show `graph_day_zones`, per-zone PendingIntents with same left/right split.

**Hourly mode**: Hide `graph_day_zones`, keep settings click on graph_view.

PendingIntent request codes: `appWidgetId * 100 + dayIndex` (text), `appWidgetId * 100 + 50 + dayIndex` (graph).

---

## Files Summary

### Modified
| File | Change |
|------|--------|
| `WeatherDatabase.kt` | MIGRATION_11_12, version 12 |
| `ForecastSnapshotEntity.kt` | fetchedAt in primaryKeys |
| `ForecastSnapshotDao.kt` | Update queries, add getForecastEvolution |
| `WeatherRepository.kt` | Expand saveForecastSnapshot, remove shouldSaveSnapshot |
| `AccuracyCalculator.kt` | maxByOrNull for latest forecast |
| `widget_weather.xml` | graph_day_zones overlay |
| `WeatherWidgetProvider.kt` | Per-day click handlers |
| `AndroidManifest.xml` | Register ForecastHistoryActivity |
| `strings.xml` | forecast_history string |

### New
| File | Purpose |
|------|---------|
| `ForecastEvolutionRenderer.kt` | Renders high/low evolution graphs (bezier curves) |
| `activity_forecast_history.xml` | Activity layout with two graph ImageViews |
| `ForecastHistoryActivity.kt` | Activity loading data and rendering graphs |

---

## Part D: Tests

Tests run after every step. Existing tests must stay green throughout.

**Run commands:**
- Unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
- Build check: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`

### D1. After Part A — WeatherRepository snapshot tests
**File:** `app/src/test/java/com/weatherwidget/data/repository/WeatherRepositoryTest.kt`

Add tests to the existing file:
- `saveForecastSnapshot saves forecasts for all future days` — mock weather list with 7 future days, verify `insertSnapshot` called for each
- `saveForecastSnapshot skips past and today dates` — verify no snapshots saved for today or earlier
- `saveForecastSnapshot saves with unique fetchedAt` — verify each snapshot has a fetchedAt value

**Run existing tests** to confirm nothing broke:
- `WeatherRepositoryTest` — existing 7 tests must pass (the `shouldSaveSnapshot` removal could affect mock expectations)

### D2. After Part A — AccuracyCalculator test
**File:** `app/src/test/java/com/weatherwidget/stats/AccuracyCalculatorTest.kt` (new)

- `getDailyAccuracyBreakdown uses latest forecast when multiple exist` — create multiple snapshots for same (targetDate, forecastDate, source) with different fetchedAt, verify the one with highest fetchedAt is used
- `getDailyAccuracyBreakdown still works with single snapshot per day` — regression test matching existing behavior

### D3. After Part B — ForecastEvolutionRenderer test
**File:** `app/src/androidTest/java/com/weatherwidget/widget/ForecastEvolutionRendererTest.kt` (new)

Following the `TemperatureGraphRendererTest` pattern (instrumented test with bitmap assertions):
- `renderHighGraph produces non-empty bitmap` — basic sanity check
- `renderHighGraph shows NWS curve in blue` — pixel color check at curve positions
- `renderHighGraph shows actual line for past dates` — check orange pixels present
- `renderHighGraph omits actual line for future dates` — check no orange line
- `renderLowGraph produces correct dimensions` — bitmap size validation
- `renderGraph handles empty data` — no crash on empty snapshot list

### D4. After Part C — Verify all existing tests still pass
Run full unit test suite + build to confirm widget click handler changes don't break anything:
```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug
```

### Test execution order
Each step below runs tests and must pass before proceeding:

| Step | What | Tests Run |
|------|------|-----------|
| A1-A2 | Schema + Entity | `./gradlew assembleDebug` (compile check) |
| A3 | saveForecastSnapshot | All unit tests + new D1 tests |
| A4-A5 | AccuracyCalculator + DAO | All unit tests + new D2 tests |
| B1 | ForecastEvolutionRenderer | All unit tests + `assembleDebug` |
| B2-B4 | Activity + layout + manifest | All unit tests + `assembleDebug` |
| C1-C2 | Widget click handlers | All unit tests + `assembleDebug` |
| Final | Everything | Full unit tests + instrumented tests (D3) |

---

## Verification (Manual)

1. Build + install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Migration: app doesn't crash on upgrade
3. Force data fetch, verify multi-day snapshots in DB
4. Widget text mode: left-half days -> forecast history, right-half -> settings
5. Widget graph mode: same left/right split
6. Forecast history activity: graphs show curves for NWS (blue) + Open-Meteo (green)
7. Past dates: orange actual line visible on both graphs
8. Future dates: curves only, no actual line
9. Various widget sizes (3, 4, 5 columns)
10. Nav arrows, API toggle, current temp toggle still work
11. Hourly mode unaffected
12. StatisticsActivity accuracy calculations still correct
