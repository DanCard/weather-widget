# Plan: Refactor Hourly Forecast Schema to Epoch Milliseconds

## Objective
Convert `HourlyForecastEntity.dateTime` from an ISO 8601 `String` to a `Long` (epoch milliseconds). This fixes the root cause of the 8-second rendering freeze by eliminating redundant string-to-date parsing in the Inverse Distance Weighting (IDW) interpolation loops.

## Background
The current implementation stores hourly timestamps as strings (e.g., `"2026-03-22T10:00"`). The rendering pipeline for the actual temperature line performs time-series math that requires comparing these timestamps. Because they are strings, the code calls `LocalDateTime.parse()` tens of thousands of times per render, which is extremely slow on Android.

## Scope & Impact
- **Database**: Schema migration from version 39 to 40.
- **Network**: All 4 API mappers must now parse timestamps before persistence.
- **UI**: All hourly view handlers (Temperature, Precip, Cloud) must be updated to use `Long` math.
- **Performance**: Expected to reduce "Actuals" processing time from ~8000ms to <50ms.

## Proposed Solution

### 1. Database & Entity Update
- Modify `HourlyForecastEntity.kt`: Change `dateTime` type to `Long`.
- Modify `HourlyForecastDao.kt`: Update all query parameters (`startDateTime`, `endDateTime`) to `Long`.
- Update `WeatherDatabase.kt`:
    - Increment version to 40.
    - Add `MIGRATION_39_40` which creates a temp table, migrates data using `unixepoch()` or manual parsing (since SQLite's `unixepoch` requires valid ISO formats, and our strings are `yyyy-MM-ddTHH:mm`), and renames it.

### 2. Network Ingestion Update
- `NwsApi.kt`: Parse `startTime` using `ZonedDateTime`.
- `OpenMeteoApi.kt`, `WeatherApi.kt`, `SilurianApi.kt`: Parse `time` string to epoch ms.
- `ForecastRepository.kt`: Update the mapping logic to handle `Long` timestamps.

### 3. UI Handler Refactor
- **`TemperatureViewHandler.kt`**: Replace `LocalDateTime` keys in maps with `Long`. Direct comparison of timestamps in `blendObservationSeries`.
- **`PrecipViewHandler.kt`** & **`CloudCoverViewHandler.kt`**: Update grouping and filtering logic.
- **`WidgetIntentRouter.kt`**: Calculate query windows as `Long` ranges instead of formatted strings.

## Implementation Plan

### Phase 1: Schema & Data Access
1.  Update `HourlyForecastEntity.kt`.
2.  Update `HourlyForecastDao.kt`.
3.  Implement `MIGRATION_39_40` in `WeatherDatabase.kt`.
    - *Note*: Since SQLite doesn't have a built-in ISO-to-Unix function that handles 'T', we may need to use `strftime('%s', REPLACE(dateTime, 'T', ' ')) * 1000`.

### Phase 2: Repository & APIs
1.  Update `ForecastRepository.kt` call sites.
2.  Update all 4 API classes to return/handle `Long` timestamps.

### Phase 3: UI & Rendering
1.  Refactor `TemperatureViewHandler` (The main performance bottleneck).
2.  Refactor `PrecipViewHandler` and `CloudCoverViewHandler`.
3.  Refactor `WidgetIntentRouter` query window logic.

### Phase 4: Validation
1.  **Add Database Migration Test**:
    - Update `app/src/androidTest/java/com/weatherwidget/data/local/DatabaseMigrationTest.kt`.
    - Create a test function `migrate39to40()`.
    - Use `helper.createDatabase(testDb, 39)` to create a legacy DB.
    - Insert test rows into `hourly_forecasts` using the old `TEXT` schema (e.g., `dateTime = '2026-03-22T10:00'`).
    - Run the migration using `helper.runMigrationsAndValidate(testDb, 40, true, WeatherDatabase.MIGRATION_39_40)`.
    - Query the new table and assert that the `dateTime` is now a `Long` matching the expected epoch milliseconds.
2.  Run UI Unit tests.
3.  Verify on emulator: Check logcat for `TEMP_OBS_SLOW` to confirm the fix.

## Verification & Testing
- **Migration Test**: Ensure data is preserved during the String -> Long conversion.
- **Functional Test**: Ensure graphs still draw correctly at the right time offsets.
- **Performance Test**: Confirm `buildHourDataMs` in logs drops below 100ms.
