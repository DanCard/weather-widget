# Plan: Rename `forecastDate` to `dateOfPrediction`

## Overview
Rename the field/column `forecastDate` to **`dateOfPrediction`** across the Kotlin codebase, Android Room database schema (`v53` $\rightarrow$ `v54`), and Desktop SQLite database schema (`v8` $\rightarrow$ `v9`). 

`dateOfPrediction` explicitly identifies the normalized calendar date (UTC midnight epoch millis) **on which the forecast prediction was generated/issued**, eliminating ambiguity between the target prediction date (`targetDate`) and the issuance date.

---

## Impacted Modules & Files

### 1. Data Models & Entities
- **`app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt`**:
  - Rename property `forecastDate` $\rightarrow$ `dateOfPrediction`.
  - Update `primaryKeys` list to `["targetDate", "dateOfPrediction", "locationLat", "locationLon", "source", "fetchedAt"]`.
- **`shared/src/main/kotlin/com/weatherwidget/data/model/Forecast.kt`** (and related shared models/mappers):
  - Rename `forecastDate` $\rightarrow$ `dateOfPrediction` if present.

### 2. Room & SQLite Database Migrations
- **`app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`**:
  - Bump Room database version from `53` to `54`.
  - Add `MIGRATION_53_54`:
    - Execute `ALTER TABLE forecasts RENAME COLUMN forecastDate TO dateOfPrediction;`
    - Update/recreate corresponding index `index_forecasts_targetDate_source_locationLat_locationLon_batchFetchedAt`.
  - Register `MIGRATION_53_54` in `.addMigrations(...)`.
  - Generate Room schema JSON `54.json`.
- **`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`**:
  - Bump Desktop `SCHEMA_VERSION` from `8` to `9`.
  - Add v9 migration to check `PRAGMA table_info(forecasts)` for `forecastDate` and rename to `dateOfPrediction`.
  - Update `CREATE TABLE IF NOT EXISTS forecasts` DDL string.

### 3. DAOs & Repositories
- **`app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`**:
  - Update `@Query` SQL statements referencing `forecastDate` (e.g. `ORDER BY dateOfPrediction DESC`, `WHERE dateOfPrediction = :dateOfPrediction`).
- **`app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`**:
  - Update instantiated `ForecastEntity(...)` calls and `dateOfPrediction` references.
- **`app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`**:
  - Update mapping logic to supply `dateOfPrediction`.
- **`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`**:
  - Update `INSERT OR REPLACE INTO forecasts` and `SELECT` SQL statements.

### 4. Logic & Features
- **`app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`**:
  - Update `it.dateOfPrediction == forecastEpoch`.
- **`app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`**:
  - Update forecast snapshot iteration `snapshot.dateOfPrediction`.

### 5. Unit & Instrumented Tests
- **`app/src/test/java/com/weatherwidget/data/local/ForecastSnapshotDaoTest.kt`**
- **`app/src/test/java/com/weatherwidget/data/repository/WeatherGapIntegrationTest.kt`**
- **`app/src/test/java/com/weatherwidget/data/repository/ForecastHistoryStorageTest.kt`**
- **`app/src/androidTest/java/com/weatherwidget/data/local/WeatherDatabaseMigrationTest.kt`** (Add `migrationFrom53To54_renamesForecastDateToDateOfPrediction`)
- All other test helper factory methods (e.g. `TestData.forecast(...)`).

---

## Execution Steps

1. **Apply Code & Entity Refactoring**:
   - Rename property in `ForecastEntity.kt`, `ForecastDao.kt`, repositories, mappers, UI classes, and DAOs.
2. **Apply Database Schema Migrations**:
   - Add `MIGRATION_53_54` to `WeatherDatabase.kt` and bump version to `54`.
   - Add v9 migration to `DesktopWeatherDatabase.kt` and bump version to `9`.
3. **Update Unit Tests & Room Schema JSON**:
   - Update all test references.
   - Run `./gradlew test` to compile and generate `54.json`.
4. **Verification**:
   - Run `./scripts/emulator-tests.sh` to verify Android Room `MIGRATION_53_54` on `emulator-5554`.
   - Run `./scripts/buildStart-desktop.sh` and verify Desktop SQLite schema migration with `sqlite3`.

---

## Verification & Testing Checklist

- [ ] `./gradlew test` passes cleanly.
- [ ] `./gradlew :desktop:test` passes cleanly.
- [ ] `./scripts/emulator-tests.sh` passes all 36+ instrumented tests on emulator.
- [ ] Android DB schema verification: `sqlite3 PRAGMA table_info(forecasts)` contains column `dateOfPrediction`.
- [ ] Desktop DB schema verification: `sqlite3 PRAGMA table_info(forecasts)` contains column `dateOfPrediction`.
