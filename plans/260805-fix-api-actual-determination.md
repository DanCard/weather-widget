# Fix API Actual Determination in Forecast History

## Problem

The "API actual" line in the Forecast History screen currently uses the **last forecast snapshot** (from `forecasts` table) as a proxy for what the API reported as the observed high/low. This is wrong — forecasts are predictions, not observations. For past dates, the APIs themselves report observed high/low temperatures, and that is what should be shown.

### Current (broken) behavior (`ForecastHistoryActivity.kt:308-333`)

- NWS: `resolveSourceSpecificActual()` looks at `forecasts` table rows, picks latest `ForecastEntity` with non-null high/low → treats the *forecast snapshot* as "API actual"
- Non-NWS: Same — latest `ForecastEntity` from `forecasts` table
- For NWS, there's a special path `selectLatestCompleteActualFromForecasts()` that adds the gate `highTemp != null && lowTemp != null`

### What should happen

- **NWS**: Use the NDFD gridpoint daily temperature extremes (already parsed by `getGridpointsBundle()` → `DailyTemperatureExtremes`, with plausibility filtering and hourly cross-check repair) — these are the official NWS observed daily highs/lows
- **Open-Meteo**: Use the `past_days=N` daily values from the forecast response — past days are ERA5-based observed actuals, not forecasts
- **Silurian / Tomorrow.io**: No native observed actuals exist — fall back to the first preferred source that has API actuals

## Data Model Change

Rename `highTemp`/`lowTemp` to indicate they are computed/blended extremes ("Location actual"), and add new API-reported columns.

```
DailyHistoryEntity (before):
  highTemp: Float          — ambiguous: could be computed or API-reported
  lowTemp: Float           — ambiguous

DailyHistoryEntity (after):
  computedHighTemp: Float  — computed/blended extreme from IDW observation pipeline ("Location actual")
  computedLowTemp: Float   — computed/blended extreme
  apiHighTemp: Float?      — API-reported observed high (NWS gridpoint, Open-Meteo past_days)
  apiLowTemp: Float?       — API-reported observed low
```

**DB migration 57→58**: Rename `highTemp` → `computedHighTemp`, `lowTemp` → `computedLowTemp`, add `apiHighTemp`/`apiLowTemp` columns.

**Shared model** (`DailyHistory` in `:shared`): same rename + new fields.

**Impact**: ~43 files (14 source + 29 test) reference `DailyHistory.highTemp`/`lowTemp`. All must be updated to `computedHighTemp`/`computedLowTemp`. This is a pure rename — no logic changes.

## Implementation Steps

### Step 1: Rename `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp`, add `apiHighTemp`/`apiLowTemp`

1. `DailyHistoryEntity.kt` — rename fields, add `apiHighTemp`/`apiLowTemp`
2. `DailyHistory.kt` (shared) — rename fields, add `apiHighTemp`/`apiLowTemp`
3. `WeatherDatabase.kt` — MIGRATION_57_58: rename existing columns + add new columns, bump version to 58
4. Update mappers (`toDailyHistory()`, `toEntity()`)
5. Update all ~43 construction sites and property accesses across source and test files
6. Desktop DB layer (`DesktopWeatherDao.kt`) — column names and queries

### Step 2: NWS — persist gridpoint daily extremes as API actuals

The NWS gridpoint `/gridpoints/{id}/{x},{y}` endpoint already returns observed daily highs/lows in `maxTemperature`/`minTemperature` properties, parsed by `getGridpointsBundle()` → `DailyTemperatureExtremes`. However, `NwsDailyMapper.mergeGridpointTemperatures()` **discards past-date values** (line 115: `if (date.isBefore(today) ... continue`).

**New function in `DailyActualsStore`**:
```kotlin
suspend fun persistNwsGridpointActuals(
    latitude: Double, longitude: Double,
    extremes: NwsApi.DailyTemperatureExtremes,
)
```
- Iterates `extremes.maxByDate`/`extremes.minByDate` for past dates
- Upserts `daily_history` rows with `apiHighTemp`/`apiLowTemp` populated
- Leaves `computedHighTemp`/`computedLowTemp` alone (blend pipeline owns those)

**Call site**: `NwsForecastMapper.fetchFromNws()` or `ForecastFetchCoordinator`, after `getGridpointsBundle()`.

### Step 3: Open-Meteo — persist past_days values as API actuals

Open-Meteo `/forecast?past_days=N` returns observed values for past dates. Currently `ACTUALS_HISTORY_DAYS=3` is passed but past-day values only go to `forecasts` table.

**New function in `DailyActualsStore`**:
```kotlin
suspend fun persistOpenMeteoPastDayActuals(
    latitude: Double, longitude: Double,
    dailyForecasts: List<DailyForecast>,
)
```
- Filter to dates before today
- Upsert `daily_history` rows with `apiHighTemp`/`apiLowTemp`

**Call site**: `ForecastFetchCoordinator.fetchAndSaveSharedForecast()`, after Open-Meteo fetch.

### Step 4: Update ForecastHistoryActivity

Replace `resolveSourceSpecificActual()` (which reads `forecasts` table as a proxy) with direct `daily_history` queries using `apiHighTemp`/`apiLowTemp`.

**Logic**:
1. Query `dailyHistoryDao.getExtremesInRange()` for the target date
2. Look for row matching requested source with non-null `apiHighTemp`/`apiLowTemp`
3. If none, fall through preferred source list (from `getVisibleSourcesOrder()`)
4. "Location actual" comes from `computedHighTemp`/`computedLowTemp`

### Step 5: Fallback for sources without API actuals

For Silurian/Tomorrow.io (no native actuals):
- Try requested source → primary source → next preferred → ... → NWS/Open-Meteo
- If nothing found, hide the "API actual" line entirely

### Step 6: Integration test for DB migration

Write a Robolectric test at `app/src/test/` that:
1. Creates a v57 database with `daily_history` rows having the old `highTemp`/`lowTemp` columns populated
2. Runs MIGRATION_57_58
3. Verifies columns were renamed (`highTemp` → `computedHighTemp`, `lowTemp` → `computedLowTemp`)
4. Verifies new `apiHighTemp`/`apiLowTemp` columns exist with null default
5. Verifies existing data survived the migration intact
6. Verifies the migrated database opens cleanly in Room (v58 schema validates)

### Step 7: Tests

- Unit test for `DailyActualsStore.persistNwsGridpointActuals()` — past/future filtering, upsert
- Unit test for `DailyActualsStore.persistOpenMeteoPastDayActuals()` — past-date filtering
- Robolectric test for `ForecastHistoryActivity` API actual fallback chain
- Update all existing tests that construct `DailyHistoryEntity`/`DailyHistory` (rename `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp`)

## Files to Modify

| File | Change |
|------|--------|
| `app/.../local/DailyHistoryEntity.kt` | Rename `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp`, add `apiHighTemp`/`apiLowTemp` |
| `app/.../local/WeatherDatabase.kt` | Add MIGRATION_57_58 (rename + add columns), bump to 58 |
| `shared/.../model/DailyHistory.kt` | Same rename + new fields |
| `shared/.../actuals/ActualsAggregator.kt` | Rename in `DailyHistory(...)` construction |
| `app/.../widget/ObservationResolver.kt` | Rename in `DailyHistoryEntity(...)` construction |
| `app/.../stats/AccuracyCalculator.kt` | Rename `.highTemp`/`.lowTemp` accesses |
| `shared/.../stats/desktop/DesktopAccuracyCalculator.kt` | Same |
| `app/.../util/DailyActualsEstimator.kt` | Rename accesses |
| `app/.../widget/handlers/DailyGraphRenderer.kt` | Rename accesses |
| `app/.../widget/handlers/DailyViewHandler.kt` | Rename accesses |
| `app/.../widget/handlers/DailyViewLogic.kt` | Rename accesses + constructions |
| `app/.../ui/ForecastHistoryActivity.kt` | Rename accesses + rewrite actual lookup |
| `desktop/.../DesktopDailyForecastModel.kt` | Rename accesses |
| `desktop/.../ForecastHistoryWindow.kt` | Rename accesses |
| `shared/.../data/local/desktop/DesktopWeatherDao.kt` | Rename column refs + construction |
| `app/.../repository/DailyActualsStore.kt` | Add `persistNwsGridpointActuals()`, `persistOpenMeteoPastDayActuals()` |
| `app/.../repository/ForecastFetchCoordinator.kt` | Call new persist functions |
| `app/.../repository/NwsForecastMapper.kt` | Pass gridpoint extremes to persist |
| `app/src/test/.../Migration57_58Test.kt` | **New**: integration test for DB migration |
| `app/src/test/.../DailyActualsStoreTest.kt` | **New**: unit tests for new persist functions |
| `app/src/test/.../ForecastHistoryActivityRoboTest.kt` | **New/update**: test API actual fallback |
| ~30+ test files | Rename `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp` |
