# Plan: Remove `locationName` Column from `forecasts` Table and Codebase

## Overview
Remove the unused `locationName` column/property from the `forecasts` database tables (`ForecastEntity` in Android Room and `forecasts` table in Desktop SQLite), DAOs, repositories, mappers, and unit/instrumented tests.

Location names are managed via user location settings/preferences and are not displayed or utilized from the `forecasts` table rows. Removing it cleans up the schema and eliminates unnecessary string parameter pass-throughs across the repository and mapper pipelines.

---

## Impacted Files & Changes

### 1. Data Models & Entities
- **`app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt`**:
  - Remove `val locationName: String = ""` property.
- **`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`**:
  - Update `CREATE TABLE IF NOT EXISTS forecasts` DDL string to remove `locationName TEXT NOT NULL DEFAULT ''`.
  - Bump `SCHEMA_VERSION` from `9` to `10`.
  - Add v10 migration: `ALTER TABLE forecasts DROP COLUMN locationName;`.

### 2. Android Room Migration (v54 $\rightarrow$ v55)
- **`app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`**:
  - Bump Room database version from `54` to `55`.
  - Add `MIGRATION_54_55`:
    - Execute `ALTER TABLE forecasts DROP COLUMN locationName;`
  - Register `MIGRATION_54_55` in `.addMigrations(...)`.
  - Export Room schema `55.json`.

### 3. Repositories, Mappers & DAOs
- **`app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`**:
  - Update any queries explicitly listing `locationName` if present.
- **`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`**:
  - Remove `locationName` from `INSERT OR REPLACE INTO forecasts` columns, values placeholders, and binding indexes.
- **`app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`**:
  - Remove `locationName` parameters from `fetchFromNws`, `mapDailyForecast`, `saveForecastSnapshot`, `getWeatherData`, and `ForecastEntity` instantiations.
- **`app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`**:
  - Remove `locationName` parameters from `getWeatherData` delegation functions.
- **`app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`**:
  - Remove `locationName` parameter and `ForecastEntity` binding.
- **`app/src/main/java/com/weatherwidget/data/repository/ClimateGapFiller.kt`**:
  - Remove `locationName` parameter and binding.
- **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`**:
  - Clean up `locationName` parameter pass-throughs when calling `getWeatherData` / `saveForecastSnapshot`.

### 4. Tests
- Update unit tests and test helpers (`TestData.kt`, `WeatherDatabaseMigrationTest.kt`, `ForecastSnapshotDaoTest.kt`, `WeatherRepositoryTest.kt`, etc.) to remove `locationName`.
- Add `migrate54To55_dropsLocationNameColumn` test to `WeatherDatabaseMigrationTest.kt`.

---

## Verification Plan

1. **Unit Tests**:
   - Run `./gradlew test` to verify compilation and unit tests across `:app`, `:shared`, and `:desktop`.
   - Verify generation of Room schema `55.json`.
2. **Android Instrumented Tests & Schema Migration**:
   - Run `./scripts/emulator-tests.sh` on `emulator-5554`.
   - Verify `PRAGMA table_info(forecasts)` on emulator database shows `user_version = 55` without `locationName`.
3. **Desktop App & Schema Migration**:
   - Run `./scripts/buildStart-desktop.sh`.
   - Verify `PRAGMA table_info(forecasts)` on desktop `weather.db` shows `user_version = 10` without `locationName`.
