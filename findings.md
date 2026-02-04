# Findings

## Codebase Discovery

### Database (v11)
- WeatherDatabase.kt: Migrations 1→11, uses `fallbackToDestructiveMigration()`
- ForecastSnapshotEntity: PK = (targetDate, forecastDate, locationLat, locationLon, source). fetchedAt exists but NOT in PK
- ForecastSnapshotDao: 4 queries + insert + insertAll + deleteOld. No getForecastEvolution query yet

### WeatherRepository
- saveForecastSnapshot (lines 124-157): Only saves TOMORROW's forecast, uses shouldSaveSnapshot dedup
- shouldSaveSnapshot (lines 96-122): After 8pm, skip if snapshot already exists for that date+source
- fetchFromBothApis (lines 245-295): Calls saveForecastSnapshot for each API result

### AccuracyCalculator
- getDailyAccuracyBreakdown (lines 105-166): Uses `forecasts.find { }` at line 133 - picks first match
- Need to change to filter+maxByOrNull for fetchedAt

### WeatherWidgetProvider Click Handlers
- Lines 667-668: `text_container` and `graph_view` both point to settingsPendingIntent
- Request code scheme: appWidgetId * 2 + offset (0=left, 1=right, 100=api, 200=view)
- setupNavigationButtons: lines 773-857
- setupApiToggle: lines 859-885

### Widget Layout (widget_weather.xml)
- FrameLayout root with clipChildren=false
- 6 day containers (day1-day6) in text_container (lines 37-322)
- graph_view ImageView (lines 324-332)
- Nav zones: 32dp invisible touch targets (lines 334-352)
- API source container at top-right (lines 395-419)

### Test Patterns
- WeatherRepositoryTest: 7 tests with mockk, no explicit saveForecastSnapshot tests
- TemperatureGraphRendererTest: Instrumented, bitmap pixel scanning for colors
- Colors: Blue #5AC8FA, Yellow #FFD60A, Orange #FF9F0A, Green #34C759

### Manifest
- Activities at lines 19-41: Config, Settings, Statistics, FeatureTour
- New ForecastHistoryActivity goes after FeatureTourActivity (after line 41)

---

## Implementation Notes (Forecast History Feature)

### Database Migration (v11 → v12)
- Added `fetchedAt` to primary key of `forecast_snapshots` table
- Migration recreates table with new PK: `(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)`
- This allows multiple snapshots per (targetDate, forecastDate, source) - one per fetch

### Repository Changes
- `saveForecastSnapshot()` now iterates through ALL future days (not just tomorrow)
- Removed `shouldSaveSnapshot()` dedup logic - no longer needed since every fetch is unique via `fetchedAt`
- Snapshots are created with the same `fetchedAt` timestamp for all days in a single fetch

### AccuracyCalculator Changes
- Changed from `forecasts.find { }` to `forecasts.filter { }.maxByOrNull { it.fetchedAt }`
- This ensures the latest forecast is used when multiple exist for the same (targetDate, forecastDate, source)

### Widget Click Handling
- **Text mode**: Per-container PendingIntents on `dayN_container` views
  - Left half (index < midpoint) → ForecastHistoryActivity
  - Right half (index >= midpoint) → SettingsActivity
- **Graph mode**: `graph_day_zones` overlay with 6 transparent FrameLayouts
  - Same left/right split logic
  - Unused zones hidden (GONE)
- **Hourly mode**: Hides `graph_day_zones`, keeps settings click on `graph_view`

### ForecastEvolutionRenderer
- Renders two separate graphs: High Temperature and Low Temperature
- X-axis: Days ahead (7d, 6d, 5d... 1d)
- Curves: Blue (#5AC8FA) for NWS, Green (#34C759) for Open-Meteo
- Actual line: Orange (#FF9F0A) dashed horizontal line for past dates
- Multiple fetches per day: Points spaced within each day's column

### PendingIntent Request Codes
- Text mode day clicks: `appWidgetId * 100 + dayIndex`
- Graph mode zone clicks: `appWidgetId * 100 + 50 + zoneIndex`
- This ensures unique request codes across all click handlers
