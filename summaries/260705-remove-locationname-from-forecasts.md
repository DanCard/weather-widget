# Summary: Remove `locationName` Column from `forecasts` Table and Codebase

## Overview
Removed the unused `locationName` column and property from the `forecasts` database tables (`ForecastEntity` in Android Room and `forecasts` table in Desktop SQLite), DAOs, repositories, mappers, workers, and unit/instrumented test suites across Android and Desktop.

Location names are managed via user location settings/preferences and are not displayed or utilized from the `forecasts` table rows. Removing it cleaned up the schema and eliminated unnecessary string parameter pass-throughs across the repository and mapper pipelines.

---

## Detailed Changes

### 1. Data Entities & Schema Migrations
* **Android Room DB (v55)**:
  * Removed `locationName` property from `ForecastEntity.kt`.
  * Bumped Room version to `55` in `WeatherDatabase.kt` and added `MIGRATION_54_55` (`ALTER TABLE forecasts DROP COLUMN locationName`).
  * Generated Room schema `55.json`.
* **Desktop SQLite DB (v10)**:
  * Removed `locationName` column from `forecasts` table DDL in `DesktopWeatherDatabase.kt`.
  * Bumped `SCHEMA_VERSION` to `10` and added `from < 10` migration (`ALTER TABLE forecasts DROP COLUMN locationName`).
  * Updated `upsertForecasts` SQL statement and bindings in `DesktopWeatherDao.kt`.

### 2. Repositories, Mappers, and Workers
* Removed unused `locationName` parameter pass-throughs from `ForecastRepository.kt`, `WeatherRepository.kt`, `NwsForecastMapper.kt`, `ClimateGapFiller.kt`, `WeatherWidgetProvider.kt`, `WeatherWidgetWorker.kt`, and `WidgetIntentRouter.kt`.

### 3. Testing
* Updated test helpers and unit/integration tests to remove `locationName` arguments from `ForecastEntity(...)`, `gapRows(...)`, and `getWeatherData(...)` calls.
* Added `migrate54To55_dropsLocationNameColumn` test case to `WeatherDatabaseMigrationTest.kt`.

---

## Verification Results

1. **Unit Tests**: Executed `./gradlew test` (all 398+ unit tests passed across `:app`, `:shared`, and `:desktop`).
2. **Android Instrumented Tests**: Executed `./scripts/emulator-tests.sh` on `emulator-5554` (all 64/64 tests passed, including database migration validation).
3. **Database Schema Inspection**:
   * **Android Emulator**: Extracted `/data/data/com.weatherwidget/databases/weather_database` from `emulator-5554` and verified via `sqlite3`: `PRAGMA user_version = 55` with `locationName` column successfully removed.
   * **Desktop App**: Rebuilt distributable via `./scripts/buildStart-desktop.sh`, executed app, and verified `~/.local/share/weather-widget/weather.db` via `sqlite3`: `PRAGMA user_version = 10` with `locationName` column successfully removed.
