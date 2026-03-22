# Fix: Delete current_temp table — unify on observations table

## Context

Tapping the current temperature on Samsung device toggles views (DAILY ↔ TEMPERATURE). The displayed temp jumps because:
- **DailyViewHandler** reads observed temp from `current_temp` table (72.48°F)
- **TemperatureViewHandler** reads from `observations` table via graph blending (71.46°F)

Having two tables for observed temperatures is the root problem. The `observations` table already stores per-station, per-timestamp readings from all 4 sources. The `current_temp` table is a redundant summary.

**Goal**: Delete `current_temp` table. All current temperature reads and writes go through `observations`.

## Key Discovery: Two Write Paths

1. **`CurrentTempRepository.refreshCurrentTemperature()`** → Fetches from each source → writes to BOTH `current_temp` and `observations` (via `_MAIN` stations). Already covered.
2. **`ForecastRepository.getWeatherData()` callback** → Forecast API response includes current temp → writes to `current_temp` ONLY. **Must be migrated.**

## Implementation

### Step 1: Store NWS blended result as `NWS_MAIN` observation

**File**: `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` (line ~87)

After IDW blending in `fetchNwsCurrent()`, store the blended result as a synthetic observation:
```kotlin
// After line 85 (Log.d for NWS IDW blend)
val blendedObs = ObservationEntity(
    stationId = "NWS_MAIN",
    stationName = "NWS Blended",
    timestamp = successfulEntities.maxOf { it.timestamp },
    temperature = blendedTemp,
    condition = closest.condition,
    locationLat = latitude,
    locationLon = longitude,
    distanceKm = 0f,
    stationType = "BLENDED",
)
observationDao.insertAll(listOf(blendedObs))
```

### Step 2: Migrate ForecastRepository callback to write observations

**File**: `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

Change the `onCurrentTempCallback` lambda signature and all call sites to write `_MAIN` observations instead of `CurrentTempEntity`. The callback currently passes `(source, temperature, observedAt, condition)`.

At each callback invocation site (NWS line 222, Open-Meteo line 253, WeatherAPI, Silurian), write a synthetic observation instead:
```kotlin
observationDao.insertAll(listOf(ObservationEntity(
    stationId = "${sourceId}_MAIN",  // e.g., "OPEN_METEO_MAIN"
    stationName = "...",
    timestamp = observedAt,
    temperature = temperature,
    condition = condition,
    locationLat = latitude,
    locationLon = longitude,
    distanceKm = 0f,
    stationType = "OFFICIAL",
)))
```

**Option A** (simpler): Remove the `onCurrentTempCallback` entirely from `ForecastRepository` — let `CurrentTempRepository.refreshCurrentTemperature()` be the sole writer. The forecast path was a bonus data source but `CurrentTempRepository` runs frequently enough.

**Option B**: Change `WeatherRepository.getWeatherData()` to write observations directly instead of using a callback.

**Recommend Option A** — less code to change, and the current temp is refreshed independently anyway.

### Step 3: Add ObservationDao query for latest `_MAIN` observations

**File**: `app/src/main/java/com/weatherwidget/data/local/ObservationDao.kt`

```kotlin
@Query("""
    SELECT * FROM observations
    WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
      AND ABS(locationLat - :lat) < 0.1
      AND ABS(locationLon - :lon) < 0.1
      AND timestamp > :sinceMs
    ORDER BY timestamp DESC
""")
suspend fun getLatestMainObservations(lat: Double, lon: Double, sinceMs: Long): List<ObservationEntity>
```

Callers pass `sinceMs` as start-of-today epoch millis (matching old `date` filter behavior).

### Step 4: Update ObservationResolver.resolveObservedCurrentTemp()

**File**: `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt` (lines 36-53)

Change parameter from `List<CurrentTempEntity>` to `List<ObservationEntity>`:
```kotlin
fun resolveObservedCurrentTemp(
    observations: List<ObservationEntity>,
    displaySource: WeatherSource,
): ObservedCurrentTemperature? {
    return observations
        .filter { inferSource(it.stationId) == displaySource.id || ... }
        .maxByOrNull { it.timestamp }
        ?.let { obs ->
            ObservedCurrentTemperature(
                temperature = obs.temperature,
                observedAt = obs.timestamp,
                source = inferSource(obs.stationId),
                rowFetchedAt = obs.fetchedAt,
            )
        }
}
```

### Step 5: Update all read sites (replace currentTempDao calls)

Replace `currentTempDao.getCurrentTemps(todayStr, lat, lon)` with `observationDao.getLatestMainObservations(lat, lon, todayStartMs)` at these locations:

| File | Lines | Count |
|------|-------|-------|
| `WidgetIntentRouter.kt` | 207, 407, 488, 720, 822, 892, 915 | 7 |
| `WeatherWidgetProvider.kt` | 153-156 | 1 |
| `WeatherWidgetWorker.kt` | 142-143, 368-369 | 2 |
| `WeatherObservationsActivity.kt` | 236 | 1 |

Each call replaces `currentTempDao.getCurrentTemps(...)` → `observationDao.getLatestMainObservations(...)`.

### Step 6: Update TemperatureViewHandler — remove graphObservedTemp override

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` (line 302)

Since both views now use observations, the `graphObservedTemp` override is no longer needed for consistency. Change:
```kotlin
val finalObsTemp = observedCurrentTemp ?: graphObservedTemp
val finalObsAt = observedAt ?: graphObservedAt
```

### Step 7: Remove CurrentTempRepository writes to currentTempDao

**File**: `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt` (lines 99-110)

Remove the `currentTempDao.insert()` block. The `fetchFromSource()` methods already write to observations via `observationDao`.

### Step 8: Remove ObservationRepository write to currentTempDao

**File**: `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` (lines 383-399)

Remove the `recomputeDailyExtremesForDay` sync-to-current_temp block. The NWS_MAIN observation from Step 1 replaces this.

### Step 9: Remove WeatherRepository callback

**File**: `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt` (lines 43-58)

Remove `currentTempDao` field and the `onCurrentTempCallback` lambda. The forecast data path no longer needs to write current temps.

**File**: `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

Remove `onCurrentTempCallback` parameter and all callback invocations.

### Step 10: Delete CurrentTempEntity and CurrentTempDao

- Delete `app/src/main/java/com/weatherwidget/data/local/CurrentTempEntity.kt`
- Delete `app/src/main/java/com/weatherwidget/data/local/CurrentTempDao.kt`

### Step 11: Database migration

**File**: `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`

- Remove `CurrentTempEntity::class` from `@Database` entities (line 12)
- Remove `abstract fun currentTempDao(): CurrentTempDao` (line 27)
- Bump version from 36 → 37
- Add `MIGRATION_36_37`:
  ```kotlin
  val MIGRATION_36_37 = object : Migration(36, 37) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("DROP TABLE IF EXISTS current_temp")
      }
  }
  ```
- Add to migration list

### Step 12: Update DI (AppModule)

**File**: `app/src/main/java/com/weatherwidget/di/AppModule.kt`

- Remove `provideCurrentTempDao()` provider (line 117)
- Remove `currentTempDao` from `ObservationRepository` constructor injection
- Remove `currentTempDao` from `CurrentTempRepository` constructor injection
- Remove `currentTempDao` from `WeatherRepository` constructor injection

### Step 13: Update tests

Update test files that reference `CurrentTempEntity`:
- `ObservationResolverTest.kt` — update to use `ObservationEntity`
- `DailyViewHandlerTest.kt` — update current temp setup
- Other test files referencing `CurrentTempEntity` — update accordingly

## Critical files to modify

| File | Change |
|------|--------|
| `ObservationRepository.kt` | Add NWS_MAIN synthetic observation; remove currentTempDao sync |
| `ObservationDao.kt` | Add `getLatestMainObservations()` query |
| `ObservationResolver.kt` | Accept `ObservationEntity` instead of `CurrentTempEntity` |
| `WidgetIntentRouter.kt` | 7 call sites: use observationDao |
| `WeatherWidgetProvider.kt` | 1 call site: use observationDao |
| `WeatherWidgetWorker.kt` | 2 call sites: use observationDao |
| `WeatherObservationsActivity.kt` | 1 call site: use observationDao |
| `TemperatureViewHandler.kt` | Swap finalObsTemp priority |
| `CurrentTempRepository.kt` | Remove currentTempDao.insert() |
| `WeatherRepository.kt` | Remove currentTempDao, remove callback |
| `ForecastRepository.kt` | Remove onCurrentTempCallback |
| `WeatherDatabase.kt` | Remove entity/DAO, add migration 36→37 |
| `AppModule.kt` | Remove DI bindings |
| `CurrentTempEntity.kt` | DELETE |
| `CurrentTempDao.kt` | DELETE |

## Verification

1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
3. On Samsung device:
   - Toggle between DAILY and TEMPERATURE views by tapping current temp
   - Verify displayed temperature is identical across both views
   - Check logs: `adb -s RFCT71FR9NT logcat --pid=$(adb -s RFCT71FR9NT shell pidof -s com.weatherwidget) | grep "headerState"` — `currentTemp=` should match between `mode=DAILY` and `mode=TEMPERATURE`
4. Verify observations table has `NWS_MAIN` entries after a refresh cycle
5. Verify the migration works (existing DB upgrades cleanly from v36 to v37)
