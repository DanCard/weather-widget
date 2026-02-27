# Observations Plan - Actuals Data Source

## Current State

### observations Table (currently named weather_observations)
```sql
CREATE TABLE weather_observations (
    stationId TEXT NOT NULL,
    stationName TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    temperature REAL NOT NULL,
    `condition` TEXT NOT NULL,
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    distanceKm REAL NOT NULL DEFAULT 0,
    stationType TEXT NOT NULL DEFAULT 'UNKNOWN',
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(stationId, timestamp, distanceKm, stationType)
);
```

### Current Usage
1. **Current temperature interpolation** - Getting temp between hourly readings
2. **Display in WeatherObservationsActivity** - Raw station data
3. **NOT accuracy calculation** - Currently uses weather_data.filter { it.isActual }

## New Table Name

Rename `weather_observations` → `observations` for consistency with `forecasts`.

**Files to rename:**
- `WeatherObservationEntity.kt` → `ObservationEntity.kt`
- `WeatherObservationDao.kt` → `ObservationDao.kt`
- `WeatherObservationsActivity.kt` → `ObservationsActivity.kt` (optional, longer name)

**SQL rename:**
```sql
ALTER TABLE weather_observations RENAME TO observations;
```

## New Responsibilities

After eliminating weather_data, weather_observations becomes the **sole source of truth for actual weather data**.

### 1. Daily High/Low Aggregation for Accuracy

Need to aggregate individual observations into daily summary:

```sql
-- Get daily high/low per station
SELECT
    date(timestamp/1000, 'unixepoch') as obsDate,
    stationId,
    MAX(temperature) as highTemp,
    MIN(temperature) as lowTemp,
    GROUP_CONCAT(DISTINCT `condition`, ', ') as conditions
FROM weather_observations
WHERE timestamp >= :startTimestamp
  AND timestamp <= :endTimestamp
GROUP BY obsDate, stationId;
```

### 2. Daily Summary per Location

For accuracy calculations, need a single daily actual (combining all nearby stations):

```kotlin
// In AccuracyCalculator
val observationsByDate = observations
    .groupBy { obs -> LocalDate.ofInstant(
        Instant.ofEpochMilli(obs.timestamp),
        ZoneId.systemDefault()
    ) }

val dailyActuals = observationsByDate.map { (date, obs) ->
    DailyActual(
        date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
        highTemp = obs.maxOf { it.temperature }.temperature,
        lowTemp = obs.minOf { it.temperature }.temperature,
        condition = deriveCondition(obs),
        stationCount = obs.size
    )
}
```

## Schema Considerations

### Option 1: Keep current schema (recommended)
Individual observations preserved, aggregation done in code.

**Pros:**
- Full granularity preserved
- Can analyze observation patterns over time
- No schema change needed
- Flexible aggregation (high/low, median, etc.)

**Cons:**
- Querying all observations for date range can be expensive
- Need in-memory aggregation for accuracy

### Option 2: Add daily summary table
Add `daily_observations` table with pre-aggregated high/low.

```sql
CREATE TABLE daily_observations (
    date TEXT NOT NULL,
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    highTemp REAL NOT NULL,
    lowTemp REAL NOT NULL,
    condition TEXT,
    stationCount INTEGER NOT NULL,
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY(date, locationLat, locationLon)
);
```

**Pros:**
- Fast accuracy queries
- Pre-computed values

**Cons:**
- Data duplication
- Need to maintain sync
- More complex
- Loses individual observation granularity

**Recommendation:** Option 1 - keep current schema, aggregate in code.

## Query Patterns

### Get observations for date range (accuracy calculation)

```kotlin
suspend fun getObservationsInRange(
    startDate: LocalDate,
    endDate: LocalDate,
    lat: Double,
    lon: Double
): List<WeatherObservationEntity> {
    val startTs = startDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
    val endTs = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000

    return weatherObservationDao.getObservationsInRange(startTs, endTs, lat, lon)
}
```

### Add to WeatherObservationDao

```kotlin
@Query("""
    SELECT * FROM weather_observations
    WHERE timestamp >= :startTs
      AND timestamp < :endTs
      AND locationLat BETWEEN :lat - 0.5 AND :lat + 0.5
      AND locationLon BETWEEN :lon - 0.5 AND :lon + 0.5
    ORDER BY timestamp ASC
""")
suspend fun getObservationsInRange(
    startTs: Long,
    endTs: Long,
    lat: Double,
    lon: Double
): List<WeatherObservationEntity>
```

### Current temperature interpolation (unchanged)

```kotlin
// Already working correctly - no changes needed
suspend fun getCurrentTemperature(
    lat: Double,
    lon: Double,
    source: WeatherSource
): Float? {
    // Uses hourly forecasts and nearby observations
    // No changes to this logic
}
```

## Accuracy Calculator Refactor

### Current (broken)
```kotlin
// Queries weather_data.filter { it.isActual } - which is wrong
val actualWeather =
    weatherDao.getWeatherRange(startDateStr, endDateStr, lat, lon)
        .filter { it.isActual }
```

### New (correct)
```kotlin
// Queries weather_observations and aggregates
val observations =
    weatherObservationDao.getObservationsInRange(startTs, endTs, lat, lon)

val dailyActuals = aggregateToDaily(observations)

suspend fun aggregateToDaily(
    observations: List<WeatherObservationEntity>
): Map<String, DailyActual> {
    val local = ZoneId.systemDefault()

    return observations.groupBy { obs ->
        Instant.ofEpochMilli(obs.timestamp)
            .atZone(local)
            .toLocalDate()
            .toString()
    }.mapValues { (_, obs) ->
        DailyActual(
            date = obs.first().let { o ->
                Instant.ofEpochMilli(o.timestamp)
                    .atZone(local)
                    .toLocalDate()
                    .toString()
            },
            highTemp = obs.maxOf { it.temperature }.temperature,
            lowTemp = obs.minOf { it.temperature }.temperature,
            condition = deriveConditionFromObservations(obs),
            stationCount = obs.size
        )
    }
}

data class DailyActual(
    val date: String,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val stationCount: Int
)
```

## Condition Derivation from Observations

```kotlin
private fun deriveConditionFromObservations(
    observations: List<WeatherObservationEntity>
): String {
    if (observations.isEmpty()) return "Unknown"

    // Use most common condition, or derive from multiple
    val conditions = observations.map { it.condition }

    // Handle common NWS observation condition formats
    val dayObs = observations.filter { obs ->
        val hour = Instant.ofEpochMilli(obs.timestamp)
            .atZone(ZoneId.systemDefault())
            .hour
        hour in 7..19
    }.ifEmpty { observations }

    val conditionCounts = dayObs.groupingBy { it.condition }.eachCount()
    val mostCommon = conditionCounts.maxByOrNull { it.value }

    return mostCommon?.key ?: conditions.first()
}
```

## Data Retention Policy

### Current
```kotlin
// In ForecastRepository.cleanOldData()
weatherObservationDao.deleteOldObservations(System.currentTimeMillis() - 259200000L)
// Keeps ~30 days of observations
```

### Recommendation: Keep longer for accuracy tracking
```kotlin
// Keep 90 days for 30-day accuracy + 60-day buffer
val cutoff = System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000L)
weatherObservationDao.deleteOldObservations(cutoff)
```

**Rationale:** AccuracyCalculator uses 30-day window by default. Need buffer for robust statistics.

## Testing Requirements

### Unit Tests
1. `aggregateToDaily` handles multiple observations per day
2. `aggregateToDaily` handles single observation per day
3. `aggregateToDaily` handles empty observations list
4. `deriveConditionFromObservations` handles various NWS condition formats
5. `deriveConditionFromObservations` prioritizes daytime observations

### Integration Tests
1. AccuracyCalculator produces same results with weather_observations as it did with weather_data
2. Forecast history displays correctly with aggregated actuals
3. Current temperature interpolation unaffected by aggregation logic

### Migration Tests
1. Existing observations remain accessible after refactor
2. Accuracy statistics calculated correctly post-migration
3. No data loss during table elimination

## Performance Considerations

### Query Cost
- `getObservationsInRange` may return thousands of observations
- Aggregation in memory is acceptable for 30-day window (~300-500 obs per day)

### Optimization (if needed)
```kotlin
// Add index on location + timestamp for range queries
CREATE INDEX idx_obs_location_timestamp
ON weather_observations(locationLat, locationLon, timestamp);
```

## Rollout Steps

1. Update WeatherObservationDao with `getObservationsInRange()`
2. Add `aggregateToDaily()` function to AccuracyCalculator
3. Update AccuracyCalculator to use observations instead of weather_data
4. Test accuracy calculation with real observation data
5. Verify current temperature interpolation still works
6. Remove all references to `isActual` flag
7. Run full test suite
8. Test on device with historical data

## Verification Checklist

- [ ] All observations for date range retrieved
- [ ] Daily aggregation produces correct high/low
- [ ] Condition derivation handles daylight hours preference
- [ ] Accuracy statistics match pre-refactor values
- [ ] Current temp interpolation unaffected
- [ ] No orphaned observation records
- [ ] Observation retention policy documented
- [ ] Performance acceptable for 90-day queries
