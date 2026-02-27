# Refactor: Unify Forecast Tables

## Current Problem

Two separate tables store the same type of data:
- `weather_data`: Current forecasts + mixed-in observations (isActual flag)
- `forecast_snapshots`: Historical forecast versions with `forecastDate`

This is redundant and allows observation data to corrupt forecast table.

## Target Design

### Unified Schema

```sql
CREATE TABLE IF NOT EXISTS "weather_data" (
    date TEXT NOT NULL,              -- Target date being forecasted
    forecastDate TEXT NOT NULL,       -- When forecast was made (ISO date)
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
    PRIMARY KEY(date, source, forecastDate, fetchedAt)
);
```

### Table Responsibilities

1. **weather_data**: ALL forecast predictions
   - Current forecasts (made today for today+future dates)
   - Historical forecasts (made in past for current/past dates)
   - Climate normals (isClimateNormal=true, forecastDate=date)
   - No isActual flag - observations go to separate table

2. **weather_observations**: Actual measurements from weather stations (unchanged)
   - Individual readings from NWS, Open-Meteo, WeatherAPI stations
   - Used for current temp interpolation and accuracy calculation

3. **forecast_snapshots**: REMOVE - redundant with unified weather_data

## Migration Strategy

### Phase 1: Add forecastDate column
```sql
ALTER TABLE weather_data ADD COLUMN forecastDate TEXT DEFAULT '';
CREATE INDEX idx_weather_data_forecastDate ON weather_data(forecastDate);
```

### Phase 2: Migrate existing data
```sql
-- Migrate forecast_snapshots to weather_data
INSERT OR REPLACE INTO weather_data (
    date, forecastDate, locationLat, locationLon, locationName,
    highTemp, lowTemp, condition, isClimateNormal, source,
    precipProbability, fetchedAt
)
SELECT
    targetDate as date, forecastDate, locationLat, locationLon,
    '' as locationName, highTemp, lowTemp, condition, 0 as isClimateNormal,
    source, NULL as precipProbability, fetchedAt
FROM forecast_snapshots;

-- For existing weather_data rows without forecastDate, set it to 'current'
UPDATE weather_data SET forecastDate = date WHERE forecastDate = '';
```

### Phase 3: Remove isActual flag (after observation handling fixed)
```sql
DELETE FROM weather_data WHERE isActual = 1;
ALTER TABLE weather_data DROP COLUMN isActual;
```

### Phase 4: Drop forecast_snapshots
```sql
DROP TABLE forecast_snapshots;
DROP INDEX index_forecast_snapshots_locationLat_locationLon;
```

## Query Patterns After Refactor

### Get latest forecast for display
```sql
SELECT * FROM weather_data
WHERE date = :targetDate
  AND source = :source
  AND forecastDate <= :targetDate
ORDER BY forecastDate DESC, fetchedAt DESC
LIMIT 1;
```

### Get forecast made yesterday for accuracy
```sql
SELECT * FROM weather_data
WHERE date = :targetDate
  AND source = :source
  AND forecastDate = :yesterdayDate
ORDER BY fetchedAt DESC
LIMIT 1;
```

### Get all forecast versions for a date
```sql
SELECT * FROM weather_data
WHERE date = :targetDate
  AND source = :source
ORDER BY forecastDate DESC, fetchedAt DESC;
```

## Code Changes Required

### ForecastRepository.kt
1. Remove `fetchAndApplyObservations()` - observations should not populate weather_data
2. Add `forecastDate` to WeatherEntity
3. Update `fetchFromNws()` to set forecastDate = LocalDate.now()
4. Update `saveForecastSnapshot()` to insert directly to weather_data with forecastDate

### AccuracyCalculator.kt
1. Query weather_data directly instead of forecastSnapshotDao
2. Remove forecastSnapshotDao dependency
3. Get actuals from weather_observations (calculate daily high/low from readings)

### WeatherDao.kt
1. Update queries to work with new PK (date, source, forecastDate, fetchedAt)
2. Add method: `getLatestForecast(date, source)`
3. Add method: `getForecastMadeOn(date, forecastDate, source)`

### WeatherEntity.kt
1. Add `forecastDate: String` field
2. Remove `isActual: Boolean` field

### ForecastSnapshotDao.kt
1. Mark as deprecated
2. Eventually delete file

## Testing Impact

1. Update all tests that use `isActual` flag
2. Update tests that query forecastSnapshots
3. Add tests for forecastDate queries
4. Verify accuracy calculation with new query patterns

## Rollout Plan

1. Implement Phase 1-2 (schema change + migration)
2. Test with new schema before Phase 3
3. Remove isActual handling code (Phase 3)
4. Drop forecast_snapshots table (Phase 4)
5. Remove ForecastSnapshotDao
6. Update AccuracyCalculator

## Benefits

1. **Single source of truth**: All forecasts in one table
2. **No data mixing**: Observations and forecasts strictly separated
3. **Rich history**: All forecast versions available for analysis
4. **Simpler queries**: No joining separate snapshot table
5. **Smaller codebase**: Remove duplicate DAOs and logic
