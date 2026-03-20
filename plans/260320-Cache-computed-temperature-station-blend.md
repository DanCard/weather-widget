# Refactor: Stop persisting NWS_MAIN to observations table

## Context

After the current_temp → observations unification (DB v36→37), the `NWS_MAIN` entry is the only **computed** value written to the observations table. It's the result of IDW spatial interpolation across multiple NWS stations — not an actual observation. The other `_MAIN` entries (`OPEN_METEO_MAIN`, `WEATHER_API_MAIN`, `SILURIAN_MAIN`) are direct API readings and stay as-is.

**Problem**: Writing computed values to the observations table pollutes what should be raw data. The raw NWS station observations (KSFO, KSJC, etc.) are already in the table — the blend can be recomputed cheaply from them.

**Side benefit**: `getObservationsInRange()` (used by daily extremes computation) currently includes `NWS_MAIN` rows alongside raw station data, contaminating the extremes calculation.

## Approach: Compute NWS blend on-the-fly

Add a top-level helper function that wraps the DAO query, computes the NWS blend from raw stations, and returns the combined list. All 11 call sites switch from `observationDao.getLatestMainObservations()` to this helper.

No in-memory cache needed — `SpatialInterpolator.interpolateIDW()` is pure arithmetic on 2-5 floats. Process restart is a non-issue since raw station data is already persisted.

## Implementation

### Step 1: Add helper function

**File**: `ObservationRepository.kt`

Add a top-level `suspend fun` (not a method on the class — avoids DI changes at call sites):

```kotlin
suspend fun getMainObservationsWithNwsBlend(
    observationDao: ObservationDao,
    lat: Double, lon: Double, sinceMs: Long
): List<ObservationEntity> {
    val dbMains = observationDao.getLatestMainObservations(lat, lon, sinceMs)
        .filter { it.stationId != "NWS_MAIN" }  // exclude legacy persisted rows

    val nwsStations = observationDao.getObservationsInRange(
        sinceMs, System.currentTimeMillis(), lat, lon
    ).filter {
        ObservationResolver.inferSource(it.stationId) == WeatherSource.NWS.id
        && !it.stationId.endsWith("_MAIN")
    }

    if (nwsStations.isEmpty()) return dbMains

    val blendedTemp = SpatialInterpolator.interpolateIDW(lat, lon, nwsStations)
        ?: return dbMains
    val closest = nwsStations.minBy { it.distanceKm }

    return dbMains + ObservationEntity(
        stationId = "NWS_MAIN",
        stationName = "NWS Blended",
        timestamp = nwsStations.maxOf { it.timestamp },
        temperature = blendedTemp,
        condition = closest.condition,
        locationLat = lat,
        locationLon = lon,
        distanceKm = 0f,
        stationType = "BLENDED",
    )
}
```

### Step 2: Remove NWS_MAIN DB write

**File**: `ObservationRepository.kt` (lines 86-98)

Delete the `blendedObs` creation and `observationDao.insertAll(listOf(blendedObs))` in `fetchNwsCurrent()`.

### Step 3: Update 11 call sites

Mechanical replacement at each site:

```kotlin
// Before:
database.observationDao().getLatestMainObservations(lat, lon, todayStartMs)
// After:
getMainObservationsWithNwsBlend(database.observationDao(), lat, lon, todayStartMs)
```

| File | Lines | Count |
|------|-------|-------|
| `WidgetIntentRouter.kt` | 207, 407, 488, 720, 822, 892, 916 | 7 |
| `WeatherWidgetProvider.kt` | 153 | 1 |
| `WeatherWidgetWorker.kt` | 142-143, 368-369 | 2 |
| `WeatherObservationsActivity.kt` | 233 | 1 |

Each file needs an import for `getMainObservationsWithNwsBlend`.

### Step 4: No DB migration needed

Old `NWS_MAIN` rows will age out via the existing 30-day retention cleanup. The `.filter { it.stationId != "NWS_MAIN" }` in the helper handles the transition period.

## Files to modify

| File | Change |
|------|--------|
| `ObservationRepository.kt` | Add `getMainObservationsWithNwsBlend()` top-level function; remove NWS_MAIN DB write |
| `WidgetIntentRouter.kt` | 7 call sites → use new helper |
| `WeatherWidgetProvider.kt` | 1 call site → use new helper |
| `WeatherWidgetWorker.kt` | 2 call sites → use new helper |
| `WeatherObservationsActivity.kt` | 1 call site → use new helper |

## Verification

1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew compileDebugKotlin`
2. Unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
3. Install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
4. On device: verify current temp still displays correctly for NWS source
5. Query observations table — confirm no new `NWS_MAIN` rows after a refresh cycle
