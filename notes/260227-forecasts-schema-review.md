# Forecasts Schema Review

## Current Schema Comparison

### forecast_snapshots (existing)
```sql
CREATE TABLE IF NOT EXISTS "forecast_snapshots" (
    targetDate TEXT NOT NULL,
    forecastDate TEXT NOT NULL,
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    highTemp REAL,
    lowTemp REAL,
    `condition` TEXT NOT NULL,
    source TEXT NOT NULL,
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
);

CREATE INDEX index_forecast_snapshots_locationLat_locationLon
ON forecast_snapshots(locationLat, locationLon);
```

### weather_data (existing, to be migrated and deleted)
```sql
CREATE TABLE IF NOT EXISTS "weather_data" (
    date TEXT NOT NULL,
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    locationName TEXT NOT NULL,
    highTemp REAL,
    lowTemp REAL,
    `condition` TEXT NOT NULL,
    isActual INTEGER NOT NULL,
    isClimateNormal INTEGER NOT NULL,
    source TEXT NOT NULL,
    stationId TEXT,
    precipProbability INTEGER,
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(date, source)
);

CREATE INDEX index_weather_data_locationLat_locationLon
ON weather_data(locationLat, locationLon);
```

## Issues with Proposed Schema

### Original proposal (INCORRECT):
```sql
PRIMARY KEY(targetDate, source, forecastDate, fetchedAt)
```

**Problem:** Removed `locationLat` and `locationLon` from PK. This would allow multiple locations to have identical forecasts, breaking the core data model where forecasts are per-location.

## Corrected forecasts Schema

```sql
CREATE TABLE IF NOT EXISTS "forecasts" (
    targetDate TEXT NOT NULL,              -- Date being forecasted
    forecastDate TEXT NOT NULL,               -- When forecast was made (ISO date)
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    locationName TEXT NOT NULL,              -- NEW: from weather_data
    highTemp REAL,
    lowTemp REAL,
    `condition` TEXT NOT NULL,
    isClimateNormal INTEGER NOT NULL DEFAULT 0,  -- NEW: from weather_data
    source TEXT NOT NULL,
    precipProbability INTEGER,                  -- NEW: from weather_data
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
);

CREATE INDEX index_forecasts_locationLat_locationLon
ON forecasts(locationLat, locationLon);
```

## Field Mapping

| Field | Source | Notes |
|--------|---------|--------|
| targetDate | forecast_snapshots.targetDate, weather_data.date | Date being forecasted |
| forecastDate | forecast_snapshots.forecastDate | When prediction was made |
| locationLat | Both | Needed for queries and PK |
| locationLon | Both | Needed for queries and PK |
| locationName | weather_data.locationName | NEW: Display name |
| highTemp | Both | Forecasted high |
| lowTemp | Both | Forecasted low |
| condition | Both | Forecasted condition |
| isClimateNormal | weather_data.isClimateNormal | NEW: Historical averages |
| source | Both | API source |
| precipProbability | weather_data.precipProbability | NEW: Rain chance |
| fetchedAt | Both | When fetched from API |

## Removed Fields

| Field | Reason |
|--------|---------|
| isActual | Wrong concept - observations should be separate |
| stationId | From weather_data but belongs in observations, not forecasts |

## PK Rationale

### Current forecast_snapshots PK:
```sql
PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
```

### Why this structure matters:

1. **targetDate** first: Primary query dimension
   - "What's the forecast for X date?"

2. **forecastDate** second: Evolution tracking
   - "How did predictions for X date change over time?"
   - Multiple forecasts for same targetDate allowed

3. **locationLat/locationLon** third: Per-location data
   - Different cities have different forecasts
   - Essential for multi-location support

4. **source** fourth: API source separation
   - NWS vs Open-Meteo vs WeatherAPI
   - Each API produces its own forecasts

5. **fetchedAt** fifth: Version tracking
   - Multiple fetches at same forecastDate
   - Latest version per (targetDate, forecastDate, source, location)

### Alternative PK Considerations

**Option A: Remove forecastDate from PK (REJECTED)**
```sql
PRIMARY KEY(targetDate, locationLat, locationLon, source, fetchedAt)
```
- Problem: Loses forecast evolution tracking
- Can't see how yesterday's prediction differs from today's

**Option B: Remove location from PK (REJECTED)**
```sql
PRIMARY KEY(targetDate, forecastDate, source, fetchedAt)
```
- Problem: Allows duplicate forecasts across locations
- Breaks multi-location architecture

**Option C: Use rowid implicit PK (REJECTED)**
```sql
-- No explicit PK, SQLite auto-assigns rowid
```
- Problem: No constraint enforcement
- Hard to query specific forecast versions
- Slower queries on common dimensions

## Query Optimization

### Index Requirements

1. **Location index** (already exists):
```sql
CREATE INDEX index_forecasts_locationLat_locationLon
ON forecasts(locationLat, locationLon);
```
   - Queries by location (common pattern)
   - Spatial clustering of data

2. **Target date index** (RECOMMENDED):
```sql
CREATE INDEX index_forecasts_targetDate
ON forecasts(targetDate);
```
   - Queries like "forecasts for next 7 days"
   - Range scans on dates

3. **Composite index for latest forecast** (RECOMMENDED):
```sql
CREATE INDEX index_forecasts_latest
ON forecasts(targetDate, source, locationLat, locationLon, forecastDate DESC, fetchedAt DESC);
```
   - Optimizes: "get latest forecast for date/location/source"
   - Covers most common display query

## Migration SQL

```sql
-- Create forecasts table with corrected schema
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
    PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
);

-- Create recommended indexes
CREATE INDEX index_forecasts_targetDate ON forecasts(targetDate);
CREATE INDEX index_forecasts_latest
ON forecasts(targetDate, source, locationLat, locationLon, forecastDate DESC, fetchedAt DESC);

-- Migrate forecast_snapshots
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

-- Migrate current forecasts from weather_data (non-actuals)
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

-- Drop old tables
DROP TABLE forecast_snapshots;
DROP INDEX index_forecast_snapshots_locationLat_locationLon;
DROP TABLE weather_data;
DROP INDEX index_weather_data_locationLat_locationLon;
```

## Final Schema Summary

### tables
| Table | Purpose | Row Count |
|--------|---------|------------|
| forecasts | All forecast predictions (current + historical) | ~5000 |
| observations | Actual measurements from stations | ~500 |

### forecasts table fields (15 total)
| Field | Type | Nullable? | Purpose |
|--------|-------|------------|---------|
| targetDate | TEXT | NO | Date being forecasted |
| forecastDate | TEXT | NO | When prediction was made |
| locationLat | REAL | NO | Latitude for location filtering |
| locationLon | REAL | NO | Longitude for location filtering |
| locationName | TEXT | NO | Human-readable location name |
| highTemp | REAL | YES | Predicted high temperature |
| lowTemp | REAL | YES | Predicted low temperature |
| condition | TEXT | NO | Predicted weather condition |
| isClimateNormal | INTEGER | NO | Historical average flag |
| source | TEXT | NO | API source (NWS/METEO/WAPI) |
| precipProbability | INTEGER | YES | Rain chance percentage |
| fetchedAt | INTEGER | NO | API fetch timestamp |

### Primary Key (6 fields)
1. targetDate
2. forecastDate
3. locationLat
4. locationLon
5. source
6. fetchedAt

### Indexes (3)
1. locationLat, locationLon (location queries)
2. targetDate (date range queries) - recommended
3. composite: targetDate, source, locationLat, locationLon, forecastDate DESC, fetchedAt DESC (latest forecast) - recommended
