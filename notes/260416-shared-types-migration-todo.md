# ForecastRepository Refactoring - To Do

## Status: COMPLETE ✓

### Phase 1: Quick Fixes (217722c)
- #2: Remove dead `isPlugged()` method ✓
- #3: Fix `locationName = ""` in saveForecastSnapshot and fetchClimateNormalsGap ✓
- #5: Cache `entities.first()` in saveHourlyEntities ✓
- #11: Replace `enabledSources.map { it.id }` with `.any` ✓
- #12: Extract `CACHE_LOOKBACK_DAYS` and `CACHE_FORECAST_DAYS` constants ✓
- #13: Remove redundant `getWeatherRange` delegate ✓
- Retention: 4 days → 6 days ✓

### Phase 2: Shared API Types (4fae586)
- Define shared `HourlyForecast`, `DailyForecast`, `ForecastResult` in `ForecastTypes.kt` ✓
- Migrate all 6 APIs to return shared types ✓
- Consolidate 7 hourly save methods → 1 `saveHourlyEntitiesFromShared` ✓
- Update tests ✓
- 968 tests passing ✓

### Phase 3: fetchFromAllApis Cleanup (9ab9431)
- #4: Replace 7 boolean params with `Set<WeatherSource>` ✓
- #7: Extract `mapDailyForecast` helper for shared types ✓
- #8: Add `safeFetch` helper for catch block dedup ✓
- #9/#10: `NwsDayAccumulator` for applyForecastPeriods/logTodayDiagnostics ✓

### Phase 4: NwsForecastMapper Extraction (7a36cd3)
- #1: Extract NWS logic to separate `NwsForecastMapper` class ✓
- Updated 11 test files to provide NwsForecastMapper instance ✓
- ForecastRepository: 962 → 680 lines (-282 lines) ✓

## Summary
All code review items completed. ForecastRepository reduced from 1113 to 680 lines (-433 lines, -38%).
