# Fix NWS DAO Queries — `LIKE 'NWS_%'` Bug

## Background

Commit `073cb37` ("Replace persisted NWS_MAIN with read-time blend") changed the
architecture so that `NWS_MAIN` observations are no longer persisted to the
database. Instead, `getMainObservationsWithComputedNwsBlend()` creates a
synthetic `NWS_MAIN` on-the-fly from stored NWS station observations. The
`!= 'NWS_MAIN'` exclusions in DAO queries were added to handle legacy persisted
rows during the transition.

However, the DAO query `getLatestNwsObservationsByStationAllTime` uses
`WHERE stationId LIKE 'NWS_%'` to find NWS observations. Real NWS station IDs
(stored via `fetchStationObservation()` at `ObservationRepository.kt:114` using
`stationId = stationInfo.id`) are raw codes like `KSJC`, `AW020`, `KNUQ`,
`KPAO`, `LOAC1` — they do NOT start with `NWS_`. This means the query matches
zero rows, so the synthetic NWS blended observation is never created.

This has been silently broken since commit `073cb37`. Emulator logs confirm:
`nwsStationObsAll=0` on every call.

## Evidence

- **Station storage**: `ObservationRepository.kt:114` — `stationId = stationInfo.id`
  (raw NWS codes, no prefix)
- **Broken query**: `ObservationDao.kt:63` — `WHERE stationId LIKE 'NWS_%'`
  (never matches)
- **Emulator log**: `D/ObservationRepository: nwsStationObsAll=0
  sinceMs=1773990000000` (every call)
- **Widget impact**: `getMainObservationsWithComputedNwsBlend()` is called from
  10+ sites across `WidgetIntentRouter` (6), `WeatherWidgetProvider` (1),
  `WeatherWidgetWorker` (2), and `WeatherObservationsActivity` (1)

## Changes

### 1. `ObservationDao.kt` — Fix `getLatestNwsObservationsByStationAllTime` (line 61-69)

**Before:**
```sql
SELECT * FROM observations
WHERE stationId LIKE 'NWS_%'
  AND stationId != 'NWS_MAIN'
  AND ABS(locationLat - :lat) < 0.1
  AND ABS(locationLon - :lon) < 0.1
ORDER BY stationId, timestamp DESC
```

**After:**
```sql
SELECT * FROM observations
WHERE api = 'NWS'
  AND ABS(locationLat - :lat) < 0.1
  AND ABS(locationLon - :lon) < 0.1
ORDER BY stationId, timestamp DESC
```

**Rationale**: Use the new `api` column (added in migration 37→38) instead of
fragile stationId prefix matching. Remove `!= 'NWS_MAIN'` — no new NWS_MAIN
rows are persisted (commit `073cb37`), and old ones are cleaned by monthly
retention (`deleteOldObservations` at `ForecastRepository.kt:804`).

### 2. `ObservationDao.kt` — Remove unused `getLatestNwsObservationsByStation` (lines 50-59)

This method is declared but never called from production or test code. Confirmed
via grep: only reference is the declaration itself. Removing it eliminates dead
code that would otherwise need the same fix.

### 3. `ObservationDao.kt` — Fix `getLatestMainObservationsExcludingNws` (lines 39-48)

**Before:**
```sql
WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
  AND stationId != 'NWS_MAIN'
  AND ABS(locationLat - :lat) < 0.1
  AND ABS(locationLon - :lon) < 0.1
  AND timestamp > :sinceMs
```

**After:**
```sql
WHERE stationId LIKE '%\_MAIN' ESCAPE '\'
  AND ABS(locationLat - :lat) < 0.1
  AND ABS(locationLon - :lon) < 0.1
  AND timestamp > :sinceMs
```

**Rationale**: Remove the `!= 'NWS_MAIN'` exclusion. No new NWS_MAIN rows are
persisted. Old legacy rows are cleaned by monthly retention. This exclusion was
transitional — it's dead code now.

### 4. `WeatherDatabase.kt` — Migration 38→39

```kotlin
val MIGRATION_38_39 =
    object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_api ON observations(api)")
        }
    }
```

Add `MIGRATION_38_39` to the migration list in `getDatabase()`. Update version
from `38` to `39`.

**Rationale**: The `api` column is now used in queries (`api = 'NWS'`). While
the observations table is small, an index keeps filtering consistent as data
grows over months of retained observations.

### 5. `DatabaseMigrationTest.kt` — Add `migrate38to39` test

Follow the existing pattern (see `migrate33to34`, `migrate35to36`):

```kotlin
@Test
fun migrate38to39() {
    helper.createDatabase(testDb, 38).apply {
        close()
    }

    val db = helper.runMigrationsAndValidate(testDb, 39, true, WeatherDatabase.MIGRATION_38_39)

    // Verify the new index exists on the observations table
    val cursor = db.query("PRAGMA index_list(observations)")
    var found = false
    while (cursor.moveToNext()) {
        val name = cursor.getString(cursor.getColumnIndex("name"))
        if (name == "index_observations_api") {
            found = true
            break
        }
    }
    cursor.close()
    assert(found) { "Index on api column should exist after migration 38 to 39" }
}
```

## What this fixes

- `getMainObservationsWithComputedNwsBlend()` will now find NWS station
  observations via `api = 'NWS'` filter
- The synthetic `NWS_MAIN` (NWS Blended) will be computed from fresh station data
- The "Current Observations" activity will show a current timestamp for NWS Blended
- Widget hourly temperature graph will use the IDW blend from stored observations
  correctly

## Verification

1. Run unit tests: `./gradlew test`
2. Run instrumented tests: `./gradlew connectedDebugAndroidTest`
3. On emulator, open Current Observations → verify NWS Blended timestamp is
   recent (not hours old)
4. Check emulator logs for `nwsStationObsAll` — should show non-zero count

## No other changes needed

- `ObservationResolver.kt:164`
  (`observations.filter { it.stationId != "NWS_MAIN" }`) — This filters NWS_MAIN
  from daily extremes computation. Still correct — if any legacy rows exist in DB,
  they shouldn't influence extremes.
- `deleteOldObservations` monthly cleanup — Already handles legacy NWS_MAIN
  retention. No change needed.
