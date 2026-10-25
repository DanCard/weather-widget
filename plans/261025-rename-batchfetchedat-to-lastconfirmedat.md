# Rename `batchFetchedAt` to `lastConfirmedAt` Across Android, Desktop, and Shared

## Why
The field and database column `batchFetchedAt` was originally named after an internal mechanism: sharing a fetch timestamp across a batch of daily forecasts.
With the introduction of deduplication and re-stamping (`DailyForecastWrite`), an unchanged forecast retains its original `fetchedAt` (first seen) while advancing this timestamp upon each fetch confirmation.
In `:shared` domain models (`DailyForecast`, `DailyForecastSnapshot`, `PriorDayForecast`), this timestamp is already called `lastConfirmedAt`.

Renaming `batchFetchedAt` -> `lastConfirmedAt` fully aligns the database schema, DAOs, entities, and business logic across both Android and Desktop.

## Database Migrations

### 1. Android Room (`WeatherDatabase.kt`)
* Bump version from 81 to 82.
* Add `MIGRATION_81_82`:
  * Drop index `index_forecasts_targetDate_source_locationLat_locationLon_batchFetchedAt`.
  * `ALTER TABLE forecasts RENAME COLUMN batchFetchedAt TO lastConfirmedAt`.
  * Create index `index_forecasts_targetDate_source_locationLat_locationLon_lastConfirmedAt ON forecasts(targetDate, source, locationLat, locationLon, lastConfirmedAt)`.
* Add migration test in `WeatherDatabaseMigrationTest.kt` verifying migration from v81 to v82.

### 2. Desktop SQLite (`DesktopWeatherDatabase.kt`)
* Bump `SCHEMA_VERSION` from 34 to 35.
* Update fresh table DDL: `lastConfirmedAt INTEGER NOT NULL` and index `idx_forecasts_confirmed ON forecasts(targetDate, source, locationLat, locationLon, lastConfirmedAt)`.
* Add migration in `migrate(conn, from, to)`:
  ```kotlin
  if (from < 35) {
      stmt.execute("DROP INDEX IF EXISTS idx_forecasts_batch")
      stmt.execute("ALTER TABLE forecasts RENAME COLUMN batchFetchedAt TO lastConfirmedAt")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_forecasts_confirmed ON forecasts(targetDate, source, locationLat, locationLon, lastConfirmedAt)")
  }
  ```

## Affected Modules & Files

### `:shared`
* `ForecastTypes.kt`: (Already has `lastConfirmedAt`).
* `DailyForecastWrite.kt`:
  * `Stored.lastConfirmedAt`
  * `plan(..., lastConfirmedAt: Long = nowMs)`
* `DailyForecastSelector.kt`:
  * `lastConfirmedAt: (T) -> Long`
* `ForecastPersistRun.kt`: references to `lastConfirmedAt`.
* `DailyHistoryRun.kt` & `DailyHistoryMaintenance.kt`: update callers/references.
* `DesktopEntities.kt`: `DesktopForecastEntity.lastConfirmedAt`.
* `DesktopWeatherDao.kt`: all SQL queries (SELECT, UPDATE, ORDER BY, MAX) updated to `lastConfirmedAt`.
* `DesktopWeatherDatabase.kt`: schema and migration.
* Contract tests & unit tests: `DailyHistoryHostContract.kt`, `ForecastPersistHostContract.kt`, `DailyForecastSelectorTest.kt`, `DailyHistoryRunTest.kt`, `DailyForecastWriteTest.kt`, `DesktopWeatherDaoTest.kt`, `DesktopRetentionPolicyTest.kt`.

### `:app` (Android)
* `ForecastEntity.kt`: property `val lastConfirmedAt: Long = System.currentTimeMillis()`, index updated to `lastConfirmedAt`.
* `ForecastDao.kt`: all Room queries updated from `batchFetchedAt` to `lastConfirmedAt`.
* `WeatherDatabase.kt`: version 82 + `MIGRATION_81_82`.
* Repository & coordinators:
  * `ForecastFetchCoordinator.kt`
  * `ForecastRepository.kt`
  * `ForecastSnapshotStore.kt`
  * `RoomForecastPersistHost.kt`
  * `ClimateGapFiller.kt`
  * `DailyHistorySnapshotter.kt`
  * `WeatherRepository.kt`
  * `DataFreshness.kt`
* Android unit & Robolectric tests:
  * `ForecastDaoSitePurgeTest.kt`
  * `ForecastSnapshotDaoTest.kt`
  * `ForecastDeduplicationBugReproTest.kt`
  * `ForecastHorizonStorageIntegrationTest.kt`
  * `ForecastOnlyHistoryRowsTest.kt`
  * `PastDayTripleBarDataIntegrationTest.kt`
  * `DailyViewHandlerTest.kt`
  * `DailyTodayResolverTodayRowTest.kt`
  * `DailyTodayResolverSnapshotStaleTest.kt`
  * `AndroidDailyParityTest.kt`
  * `WeatherDatabaseMigrationTest.kt` (add v81 -> v82 test)

### `:desktop`
* `DesktopDailyHistoryHost.kt`
* Desktop tests:
  * `DesktopForecastPersistTest.kt`
  * `DesktopWeatherDaoTest.kt`
  * `DesktopDailyHistoryHostContractTest.kt`
  * `DesktopGoogleLowShiftTest.kt`
  * `DesktopPriorForecastFreezeTest.kt`
  * `DesktopSettleForecastOverlayTest.kt`

## Verification
1. Run `./scripts/unit-tests.sh` to verify all unit tests pass across `:app`, `:desktop`, and `:shared`.
2. Generate Room schemas (via `./gradlew :app:kspDebugKotlin` or `./gradlew test`) to produce `app/schemas/.../82.json`.
3. Run Robolectric and migration tests to verify DB upgrade 81 -> 82 and desktop 34 -> 35.
4. Run `./scripts/emulator-tests.sh` for instrumented tests.
