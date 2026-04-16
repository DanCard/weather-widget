# ForecastRepository Shared Types Migration - To Do

## Status: COMPLETE ✓
- Quick fixes committed (217722c): #2, #3, #5, #11, #12, #13, retention 4→6 days
- Shared types defined: `ForecastTypes.kt` ✓
- API files migrated: All 6 APIs (OpenMeteo, OWM, VC, WAPI, Silurian, TomorrowIo) ✓
- ForecastRepository: Consolidated hourly save methods ✓
- CurrentTempRepository: Fixed ✓
- Tests: Updated and passing (968 tests) ✓

## Completed Work

### 1. ForecastRepository - Consolidated hourly save methods ✓
Replaced 7 duplicate save methods with single `saveHourlyEntitiesFromShared` that takes `sourceId: String` parameter.

### 2. ForecastRepository - Updated call sites in fetchFromAllApis ✓
All 6 call sites now use `saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.XXX.id)`.

### 3. ForecastRepository - NWS hourly save ✓
NWS still uses `NwsApi.HourlyForecastPeriod` (different structure) - kept separate, updated to inline mapping.

### 4. ForecastRepository - Fixed daily mapping ✓
TomorrowIo daily mapping now uses `day.condition` and `day.iconToken` directly (conversion happens at API construction site).

### 5. CurrentTempRepository - Fixed ✓
No issues found - already using `currentCondition`.

### 6. Tests - Updated ✓
- `TomorrowIoApiTest.kt`: Changed `currentWeatherCode` to `currentCondition`
- `SilurianApiTest.kt`: Changed `highTemp`/`lowTemp` from `Int` to `Float` (75→75.0f, 50→50.0f)

### 7. Removed unused import ✓
Removed `SharedHourlyForecast` alias, using `HourlyForecast` directly.

## Summary
All 6 API types now use shared `HourlyForecast`, `DailyForecast`, and `ForecastResult` types from `com.weatherwidget.data.model`. The conversion logic (weatherCode→condition, inches→mm, etc.) stays in each API class at the construction site. ForecastRepository now has a single hourly save method instead of 7 duplicates.
