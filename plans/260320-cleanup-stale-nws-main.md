# Clean Up Stale NWS_MAIN Rows and Filter from Current Observations

## Background

Commit `073cb37` stopped persisting `NWS_MAIN` observations to the database.
Instead, `getMainObservationsWithComputedNwsBlend()` creates a synthetic
`NWS_MAIN` on-the-fly from stored NWS station observations. However:

1. Legacy `NWS_MAIN` rows persisted before that commit still exist in the DB.
2. The "Current Observations" activity for NWS source shows these stale rows
   because the `matchesObservationSource` filter doesn't exclude `NWS_MAIN`.
3. The activity displays "NWS Blended" with an old timestamp (e.g., 9:25 AM)
   and wrong temperature (e.g., 66.7°) instead of the fresh computed blend.

The DAO query fix (`LIKE 'NWS_%'` → `api = 'NWS'`) is already deployed. But
`LIKE 'NWS_%'` was matching the stale `NWS_MAIN` rows (which DO start with
`NWS_`), returning 3486 legacy observations. With the `api = 'NWS'` fix, these
rows are correctly excluded from the blend computation.

The remaining issue is the activity displaying stale `NWS_MAIN` rows directly.

## Changes

### 1. `ObservationDao.kt` — Add delete method

```kotlin
@Query("DELETE FROM observations WHERE stationId = :stationId")
suspend fun deleteObservationsByStationId(stationId: String)
```

Generic method to delete observations by station ID. Called with `"NWS_MAIN"`
to clean up stale legacy rows.

### 2. `WeatherWidgetWorker.kt` — One-time startup cleanup

In `doWork()`, inject `WeatherDatabase` and call `cleanupLegacyNwsMainRows()` at
the start. The cleanup method:
1. Checks SharedPreferences flag `nws_main_cleanup_done` — returns early if already done
2. Counts rows before deletion: `dao.countByStationId("NWS_MAIN")`
3. Calls `dao.deleteObservationsByStationId("NWS_MAIN")`
4. Logs result via `appLogDao.log("NWS_MAIN_CLEANUP", "deleted=$count")`
5. Sets SharedPreferences flag so cleanup only runs once

This runs on the first worker execution (typically within minutes of app start).
After that, the flag prevents re-running.

**When to delete this code**: Remove the `cleanupLegacyNwsMainRows()` method from
`WeatherWidgetWorker` and the `weatherDatabase` injection 2-4 weeks after the
release containing this fix ships to all users. The `deleteObservationsByStationId`
DAO method can remain as a general-purpose utility.

### 3. `WeatherObservationsActivity.kt` — Filter out NWS_MAIN

In `WeatherObservationsSupport.matchesObservationSource()`, add
`&& stationId != "NWS_MAIN"` to the NWS branch so stale `NWS_MAIN` rows
are never displayed in the Current Observations activity, even if they
survive in the database.

**Before:**
```kotlin
WeatherSource.NWS -> sourcePrefixes.values.none { prefix -> stationId.startsWith(prefix) }
```

**After:**
```kotlin
WeatherSource.NWS -> stationId != "NWS_MAIN" && sourcePrefixes.values.none { prefix -> stationId.startsWith(prefix) }
```

Also update the corresponding test in `WeatherObservationsSupportTest.kt`.

## What this fixes

- "Current Observations" activity for NWS source no longer shows stale
  "NWS Blended" with old timestamp and wrong temperature
- Stale `NWS_MAIN` legacy rows are cleaned from the database on startup
- App logs capture how many rows were deleted for auditing

## Verification

1. Run unit tests: `./gradlew test`
2. Run instrumented tests: `./gradlew connectedDebugAndroidTest`
3. On emulator, check logs for `NWS_MAIN_CLEANUP` tag showing deleted count
4. Open Current Observations for NWS — should show only real station data
