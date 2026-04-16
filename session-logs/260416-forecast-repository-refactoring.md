# ForecastRepository Code Review & Refactoring Session

Date: 2026-04-16
Trigger: User asked "How long are hourly temperature actuals kept? I think 3 days? Can I increase that to 5 days?"

## Session Overview

This session performed a comprehensive code review of `ForecastRepository.kt` (1113 lines, God class) and executed a multi-phase refactoring that reduced it to 680 lines (-38%). The work was split across 5 commits.

---

## Phase 0: Initial Investigation

**User Question:** "How long are hourly temperature actuals kept? I think 3 days? Can I increase that to 5 days?"

**Investigation:**
- Searched for retention/cleanup logic in `ForecastRepository.kt`
- Found `cleanOldData()` at line 1105: `val fourDaysAgoTimestamp = System.currentTimeMillis() - 345600000L // 4 days`
- The constant `345600000L` was used for `observationDao.deleteOldObservations()`
- Test at `WeatherRepositoryTest.kt:238` verified 4-day retention

**Answer:** 4 days (not 3). User wanted to increase it.

**Decision:** User chose 6 days (not 5). Also requested making the number more readable using `1000 * 60 * 60 * 24 * N` format.

**Changes:**
- `ForecastRepository.kt:1105`: `345600000L` → `1000L * 60 * 60 * 24 * 6`
- `ForecastRepository.kt:1104`: `oneMonthAgoTimestamp` also reformatted
- `ForecastRepository.kt:1106`: `logsCutoffTimestamp` reformatted
- `WeatherRepositoryTest.kt:238-252`: Updated test to match 6-day retention with readable format

---

## Phase 1: Code Review

**User Request:** "code review ForecastRepository.kt"

**Findings (13 items identified):**

### High Priority
1. **God class** — 1113 lines, handles 7 API orchestrations, hourly persistence, NWS parsing, climate normals, caching, snapshots, cleanup
2. **Dead code: `isPlugged()`** (line 1051) — never called, registers receiver in @Singleton
3. **`locationName = ""`** discards location data in `saveForecastSnapshot` (line 826) and `fetchClimateNormalsGap` (line 898)
4. **Parameter explosion** — `fetchFromAllApis` has 7 boolean params (lines 246-257), caller at lines 152-164 is unreadable
5. **Fragile `entities.first()`** in `saveHourlyEntities` (line 953) — if any entity differs, query silently returns wrong results

### Medium Priority
6. **7 nearly identical `saveXxxHourlyForecasts` methods** (lines 966-1033) — only differ in `condition` derivation and `WeatherSource` constant
7. **7 duplicated ForecastEntity construction blocks** in `fetchFromAllApis` (lines 276-465)
8. **7 duplicated catch blocks** — every async block repeats CancellationException re-throw + log pattern
9. **`applyForecastPeriods` has 10 parameters** (line 686) — multiple mutable maps passed in-out
10. **`logTodayDiagnostics` has 9 parameters** (line 734)

### Low Priority
11. **Line 140** — `enabledSources.map { it.id }` creates throwaway list for `contains`
12. **Line 1065** — `getCachedData` hardcodes `minusDays(7)` and `plusDays(30)`
13. **`getWeatherRange` (line 1100)** — identical to `getForecastsInRange`, redundant delegation

**User Decision:** "Should we discuss an implementation plan or just do it?" → User chose "just do it"

---

## Phase 2: Quick Fixes (Commit 217722c)

Applied 7 independent fixes that don't require test changes:

### Fix #3: `locationName = ""` → preserve location data
- `saveForecastSnapshot`: `locationName = ""` → `locationName = forecast.locationName`
- `fetchClimateNormalsGap`: `locationName = ""` → `locationName = locationName` (parameter)

### Fix #2: Remove dead `isPlugged()` method
- Removed 6 lines of unused battery status checking code

### Fix #5: Cache `entities.first()` result
- `saveHourlyEntities`: Extract `val sample = entities.first()` before DAO query
- Avoids repeated property access on same element

### Fix #11: Replace `enabledSources.map { it.id }` with `.any`
- `forceRefresh && targetSourceId != null && enabledSources.none { it.id == targetSourceId }`
- Eliminates throwaway list allocation

### Fix #12: Named constants for cache range days
- Added `CACHE_LOOKBACK_DAYS = 7L` and `CACHE_FORECAST_DAYS = 30L` to companion object
- Replaced hardcoded `minusDays(7)` and `plusDays(30)` in `getCachedData` and `getCachedDataBySource`

### Fix #13: Remove redundant `getWeatherRange` delegate
- Removed `getWeatherRange` from `ForecastRepository` (identical to `getForecastsInRange`)
- Updated `WeatherRepository.getWeatherRange` to call `forecastRepository.getForecastsInRange` directly

### Retention: 4 days → 6 days (already applied in Phase 0)
- `sixDaysAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 6`
- Test updated to verify 6-day retention

**Test Results:** All 968 tests passing after quick fixes.

**Commit:** `217722c` — "Apply ForecastRepository code review fixes (7 items)"

---

## Phase 3: Shared API Types (Commit 4fae586)

**User Question:** "Why do we have different hourly forecast types for each api? They should be the same."

**Analysis:** Each API defines its own `HourlyForecast` and `DailyForecast` data classes despite having nearly identical fields:
- `OpenMeteoApi.HourlyForecast` — uses `weatherCode: Int`
- `OpenWeatherMapApi.HourlyForecast` — uses `condition: String`
- `VisualCrossingApi.HourlyForecast` — uses `condition: String`
- `WeatherApi.HourlyForecast` — uses `condition: String`
- `SilurianApi.HourlyForecast` — uses `condition: String`, `precipProbability: Int` (non-nullable)
- `TomorrowIoApi.HourlyForecast` — uses `weatherCode: Int`
- `NwsApi.HourlyForecastPeriod` — different class name, `shortForecast`, `startTime`/`localDate`/`localHour`

**Design Decision:** Keep what the API gives us. Store `condition: String` in the shared type. For APIs that give weather codes (OpenMeteo, TomorrowIo), convert to text in the API parse layer (as they already do via `weatherCodeToCondition()`). This avoids lossy conversions from text to WMO codes.

**Implementation:**

### New file: `ForecastTypes.kt`
```kotlin
data class HourlyForecast(
    val dateTime: Long, val temperature: Float, val condition: String,
    val precipProbability: Int? = null, val precipAmountMm: Float? = null, val cloudCover: Int? = null,
)
data class DailyForecast(
    val date: String, val highTemp: Float, val lowTemp: Float, val condition: String,
    val iconToken: String? = null, val precipProbability: Int? = null, val precipAmountMm: Float? = null,
)
data class ForecastResult(
    val currentTemp: Float? = null, val currentCondition: String? = null, val currentObservedAt: Long? = null,
    val daily: List<DailyForecast> = emptyList(), val hourly: List<HourlyForecast> = emptyList(),
)
```

### Migrated APIs (6 files):
1. **OpenMeteoApi** — `weatherCode` → `condition` via `weatherCodeToCondition()` at construction site
2. **OpenWeatherMapApi** — Direct mapping (already uses `condition: String`)
3. **VisualCrossingApi** — Direct mapping
4. **WeatherApi** — Direct mapping (also updated `getHistory()` return type)
5. **SilurianApi** — `.toFloat()` on temps, `iconToken = condition`, `precipProbability` boxes from `Int` to `Int?`
6. **TomorrowIoApi** — `weatherCode` → `condition` via `weatherCodeToCondition()` at construction site

### ForecastRepository updates:
- Consolidated 7 hourly save methods → 1 `saveHourlyEntitiesFromShared(sourceId: String)`
- Updated all 6 call sites in `fetchFromAllApis`
- NWS kept separate (uses `HourlyForecastPeriod` with different structure)

### Test fixes:
- `TomorrowIoApiTest.kt`: `currentWeatherCode` → `currentCondition`
- `SilurianApiTest.kt`: `highTemp`/`lowTemp` from `Int` to `Float` (75→75.0f, 50→50.0f)

**Net:** -137 lines (219 added, 356 removed)

**Commit:** `4fae586` — "Unify API forecast types into shared model types"

---

## Phase 4: fetchFromAllApis Cleanup (Commit 9ab9431)

### Fix #4: Replace 7 booleans with `Set<WeatherSource>`
**Before:**
```kotlin
fetchFromAllApis(latitude, longitude, locationName,
    WeatherSource.NWS in enabledSources && (shouldForceSource(WeatherSource.NWS) || isStale(...)),
    openWeatherMapApi != null && WeatherSource.OPEN_WEATHER_MAP in enabledSources && ...,
    // ... 5 more boolean expressions
)
```

**After:**
```kotlin
val sourcesToFetch = enabledSources.filter { source ->
    shouldForceSource(source) || isStale(source, cachedForecasts)
}.toSet() - WeatherSource.GENERIC_GAP

fetchFromAllApis(latitude, longitude, locationName, sourcesToFetch, openWeatherMapApi != null)
```

### Fix #8: Add `safeFetch` helper
Eliminated 7 duplicate catch blocks:
```kotlin
private suspend fun <T> safeFetch(tag: String, source: WeatherSource, block: suspend () -> T): T? {
    return try { block() }
    catch (e: CancellationException) { throw e }
    catch (exception: Exception) { logFetchFailure(tag, source, exception); null }
}
```

Each async block changed from:
```kotlin
if (shouldFetchNws) async {
    try { fetchFromNws(...) }
    catch (e: CancellationException) { throw e }
    catch (exception: Exception) { appLogDao.log("FETCH_NWS_FAIL", ...) }
}
```
To:
```kotlin
if (WeatherSource.NWS in sourcesToFetch) async {
    safeFetch("FETCH_NWS_FAIL", WeatherSource.NWS) { fetchFromNws(...) }
}
```

### Fix #7: Extract `mapDailyForecast` helper
```kotlin
private fun mapDailyForecast(day: DailyForecast, latitude: Double, longitude: Double, locationName: String, sourceId: String): ForecastEntity
```

Silurian daily mapping uses inline `DailyForecast` wrapper for `.toFloat()` conversion.

### Fix #9/#10: `NwsDayAccumulator` class
Replaced 8 mutable maps + 10/9 params with single class:
```kotlin
private class NwsDayAccumulator {
    val temperatureMap = mutableMapOf<String, Pair<Float?, Float?>>()
    val conditionMap = mutableMapOf<String, String>()
    val conditionSourceMap = mutableMapOf<String, String>()
    val highTempSourceMap = mutableMapOf<String, String>()
    val lowTempSourceMap = mutableMapOf<String, String>()
    val precipProbabilityMap = mutableMapOf<String, Int>()
    val precipAmountMap = mutableMapOf<String, Float>()
    val periodTimeMap = mutableMapOf<String, Pair<String?, String?>>()
}
```

`applyForecastPeriods`: 10 params → 3 params (`forecastPeriods`, `todayDateString`, `acc`)
`logTodayDiagnostics`: 9 params → 3 params (`todayDateString`, `todayPeriods`, `acc`)

**Net:** -101 lines (177 added, 278 removed)

**Commit:** `9ab9431` — "Deduplicate fetchFromAllApis and NWS parameter bundles"

---

## Phase 5: NwsForecastMapper Extraction (Commit 7a36cd3)

**User Decision:** "Continue" — proceed with #1: Extract NWS logic to separate class.

### New file: `NwsForecastMapper.kt` (322 lines)
`@Singleton` class with `@Inject` constructor taking `NwsApi` and `AppLogDao`.

**Extracted methods:**
- `fetchFromNws()` — returns `Pair<List<ForecastEntity>, List<HourlyForecastEntity>>` (hourly entities returned for caller to save)
- `initPrecipFromHourly()` — aggregates precip probability/amount from hourly periods
- `resolveGridQpfForHourlyPeriod()` — resolves grid QPF intervals to hourly periods
- `initConditionsFromHourly()` — derives daily conditions from hourly forecasts
- `applyForecastPeriods()` — applies NWS forecast periods to the accumulator
- `logTodayDiagnostics()` — logs today's source attribution
- `extractNwsForecastDate()` — parses ISO date strings
- `persistNwsPeriodSummary()` — logs forecast period summary
- `NwsDayAccumulator` data class (moved from ForecastRepository)
- `removePhantomFutureDays()` (moved from ForecastRepository companion object)

### ForecastRepository changes:
- Added `nwsForecastMapper: NwsForecastMapper` to constructor
- `fetchFromNws()` now delegates to `nwsForecastMapper.fetchFromNws()` and saves returned hourly entities
- Removed: `initPrecipFromHourly`, `resolveGridQpfForHourlyPeriod`, `initConditionsFromHourly`, `NwsDayAccumulator`, `applyForecastPeriods`, `logTodayDiagnostics`, `extractNwsForecastDate`, `persistNwsPeriodSummary`, `removePhantomFutureDays`
- Removed unused `NWS_PERIOD_SUMMARY_COUNT` constant

### AppModule.kt:
- Added `NwsForecastMapper` import
- Added `nwsForecastMapper` parameter to `provideForecastRepository()`

### Test updates (11 files):
All test files that construct `ForecastRepository` directly were updated to provide a `mockk<NwsForecastMapper>()` instance.

**Test failures encountered and fixed:**
1. `ForecastRepositoryPhantomDayTest.kt` — `removePhantomFutureDays` moved to `NwsForecastMapper` companion object, updated all 6 test calls
2. `NwsMiddayOverrideTest.kt` — Constructor parameter order mismatch (nwsForecastMapper was in wrong position), fixed by placing it as last parameter
3. `NwsPrecipAmountIntegrationTest.kt` — Named constructor args, added `nwsForecastMapper = NwsForecastMapper(nwsApi, db.appLogDao())`

**Net:** ForecastRepository: 962 → 680 lines (-282 lines)

**Commit:** `7a36cd3` — "Extract NwsForecastMapper to separate class"

---

## Final State

### Commits
```
089cc75 Update refactoring todo to mark all phases complete
7a36cd3 Extract NwsForecastMapper to separate class
9ab9431 Deduplicate fetchFromAllApis and NWS parameter bundles
4fae586 Unify API forecast types into shared model types
217722c Apply ForecastRepository code review fixes (7 items)
```

### Summary Statistics
- **ForecastRepository:** 1113 → 680 lines (-433 lines, -38%)
- **New files:** `ForecastTypes.kt`, `NwsForecastMapper.kt`
- **Modified files:** 6 API files, `ForecastRepository.kt`, `WeatherRepository.kt`, `AppModule.kt`
- **Test files updated:** 11 files
- **Tests:** 968 passing, 0 failing

### Remaining Work
None. All 13 code review items completed.

### Notes Written
- `notes/260416-forecast-repository-refactor.md` — Original code review + plan
- `notes/260416-shared-types-migration-todo.md` — Migration status (marked complete)
