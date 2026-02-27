# Refactor: Eliminate weather_data Table

## Current Problem

`weather_data` is a generic name and mixes:
- Current forecasts (from APIs)
- "Actuals" (observations that got mixed in via isActual flag)
- Climate normals (historical averages)

Two tables already exist with clearer purposes:
- `weather_observations`: Individual station measurements
- `forecast_snapshots`: Historical forecasts

## Target Design

### Rename forecast_snapshots → forecasts

```sql
CREATE TABLE IF NOT EXISTS "forecasts" (
    targetDate TEXT NOT NULL,          -- Date being forecasted
    forecastDate TEXT NOT NULL,       -- When forecast was made
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    locationName TEXT NOT NULL,
    highTemp REAL,
    lowTemp REAL,
    `condition` TEXT NOT NULL,
    isClimateNormal INTEGER NOT NULL,  -- True for historical averages
    source TEXT NOT NULL,
    precipProbability INTEGER,
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(targetDate, source, forecastDate, fetchedAt)
);
```

### Table Responsibilities

1. **forecasts** (renamed from forecast_snapshots)
   - ALL forecast predictions: current and historical
   - Current forecasts: made today for today+future dates
   - Historical forecasts: made in past for any date
   - Climate normals: isClimateNormal=true, forecastDate=targetDate
   - No isActual flag - strictly predictions

2. **observations** (renamed from weather_observations)
   - Actual measurements from weather stations
   - Individual readings (not aggregated)
   - Used for current temp interpolation
   - Source of truth for accuracy calculation

3. **weather_data**: DELETE - eliminate generic name and data mixing

## Migration Strategy

### Phase 1: Rename forecast_snapshots and weather_observations
```sql
-- Rename weather_observations to observations
ALTER TABLE weather_observations RENAME TO observations;

-- Create new forecasts table with improved schema
CREATE TABLE IF NOT EXISTS "forecasts" (
    targetDate TEXT NOT NULL,
    forecastDate TEXT NOT NULL,
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    locationName TEXT DEFAULT '',
    highTemp REAL,
    lowTemp REAL,
    `condition` TEXT NOT NULL,
    isClimateNormal INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL,
    precipProbability INTEGER,
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(targetDate, source, forecastDate, fetchedAt)
);

-- Migrate existing forecast_snapshots data
INSERT OR REPLACE INTO forecasts (
    targetDate, forecastDate, locationLat, locationLon, locationName,
    highTemp, lowTemp, condition, isClimateNormal, source,
    precipProbability, fetchedAt
)
SELECT
    targetDate, forecastDate, locationLat, locationLon, '' as locationName,
    highTemp, lowTemp, condition, 0 as isClimateNormal, source,
    NULL as precipProbability, fetchedAt
FROM forecast_snapshots;

-- Drop old table
DROP TABLE forecast_snapshots;
DROP INDEX index_forecast_snapshots_locationLat_locationLon;
```

### Phase 2: Migrate current forecasts to forecasts table
```sql
-- Copy current forecasts from weather_data (those that aren't historical actuals)
INSERT OR REPLACE INTO forecasts (
    targetDate, forecastDate, locationLat, locationLon, locationName,
    highTemp, lowTemp, condition, isClimateNormal, source,
    precipProbability, fetchedAt
)
SELECT
    date as targetDate, date as forecastDate, locationLat, locationLon, locationName,
    highTemp, lowTemp, condition, isClimateNormal, source,
    precipProbability, fetchedAt
FROM weather_data
WHERE isActual = 0 OR isActual IS NULL;
```

### Phase 3: Delete weather_data table
```sql
DROP TABLE weather_data;
DROP INDEX index_weather_data_locationLat_locationLon;

-- Final table names
-- forecasts (renamed from forecast_snapshots)
-- observations (renamed from weather_observations)
```

## Query Patterns After Refactor

### Get latest forecast for display
```sql
SELECT * FROM forecasts
WHERE targetDate = :targetDate
  AND source = :source
  AND forecastDate <= :targetDate
ORDER BY forecastDate DESC, fetchedAt DESC
LIMIT 1;
```

### Get forecast made yesterday for accuracy
```sql
SELECT * FROM forecasts
WHERE targetDate = :targetDate
  AND source = :source
  AND forecastDate = :yesterdayDate
ORDER BY fetchedAt DESC
LIMIT 1;
```

### Get all forecast versions for a date
```sql
SELECT * FROM forecasts
WHERE targetDate = :targetDate
  AND source = :source
ORDER BY forecastDate DESC, fetchedAt DESC;
```

### Get forecasts in date range
```sql
SELECT * FROM forecasts
WHERE targetDate >= :startDate
  AND targetDate <= :endDate
  AND source = :source
  AND forecastDate <= :targetDate  -- Only latest forecast per day
GROUP BY targetDate
ORDER BY targetDate ASC;
```

## Code Changes Required

### Create ForecastDao.kt
1. Rename ForecastSnapshotDao.kt → ForecastDao.kt
2. Rename ForecastSnapshotEntity → ForecastEntity
3. Update all queries to use `forecasts` table
4. Add methods:
   - `getLatestForecast(targetDate, source)`
   - `getForecastMadeOn(targetDate, forecastDate, source)`
   - `getForecastsInRange(startDate, endDate, source)`

### Create ObservationDao.kt
1. Rename WeatherObservationDao.kt → ObservationDao.kt
2. Rename WeatherObservationEntity → ObservationEntity
3. Update all queries to use `observations` table
4. Add methods:
   - `getObservationsInRange(startTs, endTs, lat, lon)`

### Remove WeatherDao.kt
1. All weather_data queries moved to ForecastDao or ObservationDao
2. Delete file

### Remove WeatherEntity.kt
1. Use ForecastEntity for forecasts
2. Use ObservationEntity for observations

### Update ForecastRepository.kt
1. Remove `fetchAndApplyObservations()` - no mixing observations into forecasts
2. Update `fetchFromNws()` to use ForecastDao
3. Update `saveForecastSnapshot()` → `saveForecast()` inserting to forecasts
4. Remove `mergeWithExisting()` - not needed with proper separation

### Update AccuracyCalculator.kt
1. Query ForecastDao instead of forecastSnapshotDao
2. Get actuals from ObservationDao
3. Calculate daily high/low from individual observation readings:
   ```kotlin
   val obsByDate = observations.groupBy { date(it.timestamp) }
   val dailyHighLow = obsByDate.map { (date, obs) ->
       date to (obs.maxOf { it.temperature } to obs.minOf { it.temperature })
   }
   ```

### Update all other files
1. Any import of WeatherDao → ForecastDao
2. Any import of WeatherEntity → ForecastEntity
3. Any import of WeatherObservationDao → ObservationDao
4. Any import of WeatherObservationEntity → ObservationEntity
5. Any use of weather_data table → forecasts table
6. Any use of weather_observations table → observations table
7. Search and replace: `weather_data` → `forecasts`
8. Search and replace: `weather_observations` → `observations`

### Update Climate Normal handling
1. Climate normals stored in forecasts with `isClimateNormal=true`
2. Loaded from same table with flag filter
3. No separate climate_normal table needed

## Testing Impact

1. Update all tests referencing WeatherDao
2. Update all tests referencing WeatherObservationDao
3. Update tests using WeatherEntity
4. Update tests using WeatherObservationEntity
5. Update tests for accuracy calculation
6. Add tests for new ForecastDao methods
7. Add tests for ObservationDao.getObservationsInRange()
8. Test migration from old schema to new schema
9. Verify current temp interpolation still works

## Rollout Plan

1. Rename ForecastSnapshotDao.kt → ForecastDao.kt
2. Rename WeatherObservationDao.kt → ObservationDao.kt
3. Rename ForecastSnapshotEntity → ForecastEntity
4. Rename WeatherObservationEntity → ObservationEntity
5. Implement database migration:
   a. Rename weather_observations → observations
   b. Create forecasts table
   c. Migrate forecast_snapshots → forecasts
   d. Migrate weather_data forecasts → forecasts
   e. Drop forecast_snapshots
   f. Drop weather_data
3. Update ForecastRepository to use ForecastDao
4. Update AccuracyCalculator
5. Update all consuming code
6. Remove WeatherDao.kt and WeatherEntity.kt
7. Run full test suite
8. Test on device with existing data

## Benefits

1. **Clear separation**: Forecasts vs observations are distinct
2. **Clean, parallel naming**: `forecasts` and `observations` - no prefix pollution
3. **No data mixing**: Impossible to put observations in forecast table
4. **Rich history**: All forecast versions tracked naturally
5. **Single source of truth**: No ambiguity about data provenance
6. **Simpler queries**: No isActual filtering needed
7. **Better code clarity**: ObservationEntity, ForecastEntity are unambiguous

## Data Mapping Reference

| Old table/column | New table/column |
|-----------------|------------------|
| weather_data.date | forecasts.targetDate |
| weather_data.highTemp | forecasts.highTemp |
| weather_data.lowTemp | forecasts.lowTemp |
| weather_data.isActual | REMOVED (wrong concept) |
| weather_data.isClimateNormal | forecasts.isClimateNormal |
| weather_data.* | forecasts.* |
| forecast_snapshots.targetDate | forecasts.targetDate |
| forecast_snapshots.forecastDate | forecasts.forecastDate |
| forecast_snapshots.highTemp | forecasts.highTemp |
| forecast_snapshots.lowTemp | forecasts.lowTemp |
| forecast_snapshots.* | forecasts.* |
| weather_observations.* | observations.* |
