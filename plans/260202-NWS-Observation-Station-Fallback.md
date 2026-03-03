# Plan: NWS Observation Station Fallback

## Summary

Implement a fallback mechanism that tries multiple NWS observation stations when the nearest station has missing historical weather data. Currently, if the first (nearest) station has no data for a specific date, the system returns null and the widget shows forecast-only data. This plan adds sequential retry logic to try up to 5 nearby stations and caches the station list to reduce API calls.

## Problem Statement

**Current Behavior** (`WeatherRepository.kt:380-421`):
- Takes only the first station from NWS API response (`stations.first()`)
- If that station has no observations for a date, returns `null`
- No retry mechanism exists
- Result: Missing historical data even when nearby stations have complete data

**Desired Behavior**:
- Try up to 5 nearby stations sequentially when data is missing
- Stop immediately when data is found (early termination)
- Cache station list for 24 hours to reduce API calls
- Log which station provided data for observability

## Implementation Strategy

### 1. Add Station ID to Database Schema

**File**: `app/src/main/java/com/weatherwidget/data/local/WeatherEntity.kt`

Add `stationId` field after line 21:

```kotlin
@Entity(
    tableName = "weather_data",
    primaryKeys = ["date", "source"]
)
data class WeatherEntity(
    val date: String,
    val locationLat: Double,
    val locationLon: Double,
    val locationName: String,
    val highTemp: Int,
    val lowTemp: Int,
    val currentTemp: Int?,
    val condition: String,
    val isActual: Boolean,
    val source: String = "Unknown",
    val stationId: String? = null,  // NEW: NWS observation station ID (e.g., "KSFO")
    val fetchedAt: Long = System.currentTimeMillis()
)
```

**Notes**:
- Nullable because forecast data doesn't have a station ID (only observations do)
- Will be populated only when `isActual = true` (historical observations)
- Will be `null` for forecast data (`isActual = false`)

### 2. Add Database Migration

**File**: `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`

Update version from 7 to 8 and add migration:

```kotlin
@Database(
    entities = [WeatherEntity::class, ForecastSnapshotEntity::class, HourlyForecastEntity::class],
    version = 8,  // Changed from 7
    exportSchema = false
)
abstract class WeatherDatabase : RoomDatabase() {
    // ... existing code ...

    companion object {
        @Volatile
        private var INSTANCE: WeatherDatabase? = null

        // Add migration 7 -> 8
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add stationId column (nullable)
                database.execSQL("ALTER TABLE weather_data ADD COLUMN stationId TEXT DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): WeatherDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WeatherDatabase::class.java,
                    "weather_database"
                )
                    .addMigrations(MIGRATION_7_8)  // Add this migration
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

### 3. Add Station Caching (Reduces API Calls)

**File**: `WeatherRepository.kt`

Add cache helper functions after line 421:

```kotlin
private fun getCachedStations(stationsUrl: String): List<String>? {
    val key = "observation_stations_${stationsUrl.hashCode()}"
    val timeKey = "observation_stations_time_${stationsUrl.hashCode()}"

    val cacheTime = prefs.getLong(timeKey, 0L)
    val cacheTtl = 24 * 60 * 60 * 1000L  // 24 hours
    if (System.currentTimeMillis() - cacheTime > cacheTtl) {
        return null  // Cache expired
    }

    val cached = prefs.getString(key, null) ?: return null
    return cached.split(",").filter { it.isNotBlank() }
}

private fun cacheStations(stationsUrl: String, stations: List<String>) {
    val key = "observation_stations_${stationsUrl.hashCode()}"
    val timeKey = "observation_stations_time_${stationsUrl.hashCode()}"

    prefs.edit()
        .putString(key, stations.joinToString(","))
        .putLong(timeKey, System.currentTimeMillis())
        .apply()
}
```

### 4. Replace `fetchDayObservations` with Fallback Logic

**File**: `WeatherRepository.kt` (lines 380-421)

**Change return type from `Pair<Int, Int>?` to `Triple<Int, Int, String>?`** to include station ID.

Replace the entire function with:

```kotlin
private suspend fun fetchDayObservations(
    stationsUrl: String,
    date: LocalDate
): Triple<Int, Int, String>? {  // CHANGED: Now returns (high, low, stationId)
    try {
        // Try cached station list first
        var stations = getCachedStations(stationsUrl)
        if (stations == null || stations.isEmpty()) {
            Log.d(TAG, "fetchDayObservations: Fetching station list from API")
            stations = nwsApi.getObservationStations(stationsUrl)
            if (stations.isEmpty()) {
                Log.w(TAG, "fetchDayObservations: No observation stations found")
                return null
            }
            cacheStations(stationsUrl, stations)
        } else {
            Log.d(TAG, "fetchDayObservations: Using cached stations (${stations.size} total)")
        }

        // Try up to 5 stations
        val maxRetries = 5
        val stationsToTry = stations.take(maxRetries)

        for ((index, stationId) in stationsToTry.withIndex()) {
            Log.d(TAG, "fetchDayObservations: Trying station $stationId (${index + 1}/${stationsToTry.size}) for $date")

            try {
                // Fetch observations for the specified day
                val localZone = java.time.ZoneId.systemDefault()
                val startTime = date.atStartOfDay(localZone)
                    .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
                val endTime = date.plusDays(1).atStartOfDay(localZone)
                    .format(java.time.format.DateTimeFormatter.ISO_INSTANT)

                val observations = nwsApi.getObservations(stationId, startTime, endTime)
                if (observations.isEmpty()) {
                    Log.w(TAG, "fetchDayObservations: No observations from $stationId for $date - trying next")
                    continue  // Try next station
                }

                Log.i(TAG, "fetchDayObservations: SUCCESS - Got ${observations.size} observations from $stationId for $date")

                // Calculate high/low from observations (convert C to F)
                val temps = observations.map { (it.temperatureCelsius * 9 / 5 + 32).toInt() }
                val high = temps.maxOrNull() ?: continue
                val low = temps.minOrNull() ?: continue

                Log.i(TAG, "fetchDayObservations: Station $stationId provided data for $date (H:$high L:$low) after ${index + 1} attempts")

                return Triple(high, low, stationId)  // CHANGED: Return station ID

            } catch (e: Exception) {
                Log.w(TAG, "fetchDayObservations: Station $stationId failed for $date: ${e.message}")
                // Continue to next station
            }
        }

        // All stations failed
        Log.w(TAG, "fetchDayObservations: All ${stationsToTry.size} stations failed for $date")
        return null

    } catch (e: Exception) {
        Log.e(TAG, "fetchDayObservations: Error for $date: ${e.message}", e)
        return null
    }
}
```

### 5. Update Observation Data Usage to Store Station ID

**File**: `WeatherRepository.kt` (lines 320-330)

Update the code that calls `fetchDayObservations` to handle the station ID:

**Before**:
```kotlin
val observationData = fetchDayObservations(gridPoint.observationStationsUrl, date)
if (observationData != null) {
    weatherByDate[dateStr] = observationData.first to observationData.second
    conditionByDate[dateStr] = "Observed"
    Log.d(TAG, "fetchFromNws: Got observations for $dateStr H=${observationData.first} L=${observationData.second}")
}
```

**After**:
```kotlin
val observationData = fetchDayObservations(gridPoint.observationStationsUrl, date)
if (observationData != null) {
    weatherByDate[dateStr] = observationData.first to observationData.second
    stationByDate[dateStr] = observationData.third  // NEW: Track station ID
    conditionByDate[dateStr] = "Observed"
    Log.d(TAG, "fetchFromNws: Got observations for $dateStr H=${observationData.first} L=${observationData.second} from station ${observationData.third}")
}
```

Add tracking map at the beginning of `fetchFromNws` (around line 290):

```kotlin
val weatherByDate = mutableMapOf<String, Pair<Int, Int>>()
val conditionByDate = mutableMapOf<String, String>()
val stationByDate = mutableMapOf<String, String>()  // NEW: Track which station provided data
```

Update the `WeatherEntity` creation to include station ID (around line 365):

```kotlin
return weatherByDate.map { (date, temps) ->
    WeatherEntity(
        date = date,
        locationLat = lat,
        locationLon = lon,
        locationName = locationName,
        highTemp = temps.first,
        lowTemp = temps.second,
        currentTemp = null,
        condition = conditionByDate[date] ?: "Unknown",
        isActual = conditionByDate[date] == "Observed",
        source = "NWS",
        stationId = stationByDate[date],  // NEW: Include station ID (null for forecast data)
        fetchedAt = System.currentTimeMillis()
    )
}
```

## Scope Clarification

### What This Affects

**✓ Historical Observations** (Past Weather):
- Observation stations are ONLY used for historical weather data (past 7 days)
- `fetchDayObservations` retrieves actual observed high/low temps
- Creates `WeatherEntity` records with `isActual = true`
- Station ID will be populated for these records

**✗ Daily Forecasts** (Future Weather):
- Daily forecasts come from NWS forecast periods and Open-Meteo
- Do NOT use observation stations at all
- Creates `WeatherEntity` records with `isActual = false`
- Station ID will be `null` for these records

**✗ Hourly Forecasts**:
- Hourly forecasts come from NWS grid point hourly forecasts and Open-Meteo
- Do NOT use observation stations
- Stored in `HourlyForecastEntity` (separate table)
- No station ID field in hourly forecasts

**Summary**: This feature ONLY improves historical observation data quality. Forecasts are unaffected.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Max stations to try** | 5 | Balances thoroughness vs API rate limiting |
| **Station list caching** | Yes, 24hr TTL | Stations rarely change; reduces API calls from ~8/fetch to ~1/day |
| **Store station ID** | Yes, in database | Enables debugging, transparency, and future analytics |
| **Remember successful stations** | No (MVP) | Station data availability varies by date; premature optimization |
| **Retry strategy** | Sequential with early termination | Most efficient - stops when data found |
| **Parallel queries** | No | Sequential is simpler and stops early; minimal performance impact |

## Performance Analysis

### API Call Impact

**Current System** (per weather fetch):
- 1 station list fetch
- 8 observation queries (days 0-7)
- **Total: 9 API calls**

**New System (Best Case)** - first station has data:
- 0 station list fetches (cached)
- 8 observation queries
- **Total: 8 API calls** (11% improvement)

**New System (Worst Case)** - all 5 stations fail for all dates:
- 1 station list fetch (or 0 if cached)
- 40 observation queries (8 dates × 5 retries)
- **Total: 40-41 API calls**

**New System (Realistic)** - 20% fallback rate:
- 0 station list fetches (cached after first fetch)
- ~10 observation queries (8 × 1.2 average)
- **Total: ~10 API calls** (negligible impact)

### Cache Benefits

- **Before**: Fetch station list every update (60-480 min intervals)
- **After**: Fetch station list once per 24 hours
- **Daily savings**: 23-47 API calls eliminated

## Critical Files

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/java/com/weatherwidget/data/local/WeatherEntity.kt` | Modify | Add `stationId` field |
| `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt` | Modify | Increment version to 8, add migration 7→8 |
| `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt` | Modify | Add caching, fallback logic, track and store station IDs |

## Testing Strategy

### Manual Testing

1. **Build and install**:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug
   ```

2. **Monitor logs**:
   ```bash
   adb logcat | grep "fetchDayObservations"
   ```

3. **Expected log patterns**:

   **First fetch (cache miss)**:
   ```
   fetchDayObservations: Fetching station list from API
   fetchDayObservations: Trying station KSFO (1/5) for 2026-02-01
   fetchDayObservations: SUCCESS - Got 24 observations from KSFO for 2026-02-01
   fetchDayObservations: Station KSFO provided data for 2026-02-01 (H:68 L:52) after 1 attempts
   ```

   **Subsequent fetches (cache hit)**:
   ```
   fetchDayObservations: Using cached stations (15 total)
   fetchDayObservations: Trying station KSFO (1/5) for 2026-02-01
   fetchDayObservations: SUCCESS - Got 24 observations from KSFO for 2026-02-01
   ```

   **Fallback scenario**:
   ```
   fetchDayObservations: Trying station KSFO (1/5) for 2026-01-15
   fetchDayObservations: No observations from KSFO for 2026-01-15 - trying next
   fetchDayObservations: Trying station KOAK (2/5) for 2026-01-15
   fetchDayObservations: SUCCESS - Got 18 observations from KOAK for 2026-01-15
   fetchDayObservations: Station KOAK provided data for 2026-01-15 (H:65 L:48) after 2 attempts
   ```

4. **Verify widget display**:
   - Historical dates should show observed data (not forecast-only)
   - Yesterday's data should be more complete than before
   - Check Settings → API Call Log for reasonable call volume

5. **Verify database storage**:
   ```bash
   adb shell "run-as com.weatherwidget sqlite3 /data/data/com.weatherwidget/databases/weather_database 'SELECT date, highTemp, lowTemp, isActual, stationId FROM weather_data WHERE isActual=1 ORDER BY date DESC LIMIT 5;'"
   ```
   - Should see station IDs (e.g., "KSFO", "KOAK") for observed data
   - Forecast data should have NULL station IDs

5. **Test cache expiration**:
   - Wait 25+ hours
   - Trigger widget refresh
   - Logs should show "Fetching station list from API" again

### Unit Testing (Optional)

Add tests to `WeatherRepositoryTest.kt`:

1. **Test fallback when first station has no data**
2. **Test all stations fail (returns null)**
3. **Test station cache is used on second call**

## Rollback Plan

If issues arise:

1. **Quick rollback**: Change `maxRetries` from 5 to 1 in the code
   - Reverts to single-station behavior
   - Keeps caching benefits

2. **Full rollback**: `git revert` the commit
   - Restores original implementation

## Verification

**Success Criteria**:
- ✓ Widget shows more complete historical data (fewer missing days)
- ✓ Logs show fallback to additional stations when needed
- ✓ Logs display which station provided data for each date
- ✓ Station IDs stored in database for observation records
- ✓ Station list cached and reused (visible in logs)
- ✓ API call volume remains reasonable (<50 calls per fetch)
- ✓ Database migration completes without errors
- ✓ No crashes or errors in error scenarios

**Monitoring** (post-deployment):
- Watch for "All X stations failed" messages (indicates legitimate data gaps)
- Monitor average stations per query (should be ~1.1-1.3)
- Verify cache hit rate (should be >95% after initial fetch)

## Estimated Code Changes

| File | Lines Added | Lines Modified | Lines Removed | Net Change |
|------|-------------|----------------|---------------|------------|
| WeatherEntity.kt | 1 | 0 | 0 | +1 |
| WeatherDatabase.kt | 10 | 2 | 0 | +12 |
| WeatherRepository.kt | 55 | 35 | 25 | +65 |
| **Total** | **66** | **37** | **25** | **+78** |
