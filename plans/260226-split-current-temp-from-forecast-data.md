# Split `weather_data` into Forecast + Current Temp Tables

## Context
The `weather_data` table mixes daily forecast data with current temperature observations. The current temp fetch pipeline does `existing.copy(fetchedAt = now)` on today's row, which poisons `fetchedAt` semantics. `isSourceStale()` sees a fresh `fetchedAt` from current temp updates and skips full forecast refreshes — causing Samsung to show stale NWS forecast (74°) while emulator had the updated value (76°). The `date >= tomorrow` filter already applied is a band-aid. The real fix: separate these two data types into distinct tables.

## New Entity: `CurrentTempEntity`

**New file:** `app/src/main/java/com/weatherwidget/data/local/CurrentTempEntity.kt`
- Table: `current_temp`, PK: `(date, source)`
- Fields: `date`, `source`, `locationLat`, `locationLon`, `temperature` (Float), `observedAt` (Long), `condition` (String?), `fetchedAt` (Long)

**New file:** `app/src/main/java/com/weatherwidget/data/local/CurrentTempDao.kt`
- `getCurrentTemp(date, source, lat, lon)` → `CurrentTempEntity?`
- `getCurrentTemps(date, lat, lon)` → `List<CurrentTempEntity>` (all sources for today)
- `insert(entity)`
- `deleteOldData(cutoffTime)`

## Migration 22→23

**File:** `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`

1. Create `current_temp` table + index
2. Copy existing `currentTemp`/`currentTempObservedAt` from `weather_data` → `current_temp`
3. Recreate `weather_data` without `currentTemp` and `currentTempObservedAt` columns (SQLite requires table recreation to drop columns)
4. Bump version to 23, register entity + DAO + migration

## Remove Fields from WeatherEntity

**File:** `app/src/main/java/com/weatherwidget/data/local/WeatherEntity.kt`
- Remove `currentTemp: Float?`
- Remove `currentTempObservedAt: Long?`

## Update Writers (2 locations)

### A. Current temp fetch pipeline
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt` ~line 1978

Replace:
```kotlin
weatherDao.insertWeather(existing.copy(currentTemp=..., fetchedAt=now))
```
With:
```kotlin
currentTempDao.insert(CurrentTempEntity(date, source, lat, lon, temp, observedAt, condition, now))
```
**No longer touches `weather_data.fetchedAt`** — this is the core fix.

### B. Full sync (Open-Meteo / WeatherAPI initial current temp)
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt` ~lines 1479, 1516

Stop setting `currentTemp`/`currentTempObservedAt` on `WeatherEntity`. Instead, write a separate `CurrentTempEntity` when the API returns a current temp for today.

### C. Dedup logic
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt` ~line 508

Remove `new.currentTemp == existing.currentTemp` check — no longer relevant.

## Update Readers (5 production files)

### A. `ObservationResolver.kt` — primary consumer
**File:** `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`

Change `resolveObservedCurrentTemp()` to accept `List<CurrentTempEntity>` instead of `List<WeatherEntity>`. The filtering logic (`date == todayStr && currentTemp != null`) simplifies to just source matching since all rows in `current_temp` have a temperature.

### B. `DailyViewHandler.kt` — duplicated inline logic
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` ~lines 145-161

Add `currentTempList: List<CurrentTempEntity>` parameter to `updateWidget()`. Replace inline filtering with call to updated `ObservationResolver`. Update all 6 call sites:
- `WidgetIntentRouter.kt`: lines 174, 361, 426, 552, 643
- `WeatherWidgetProvider.kt`: line 673

Each call site already loads data from DAOs on a background thread — add `currentTempDao.getCurrentTemps(todayStr, lat, lon)` alongside existing queries.

### C. `WeatherObservationsActivity.kt` ~lines 162-167
**File:** `app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt`

Query `CurrentTempDao` instead of reading `WeatherEntity.currentTemp`.

### D. `WeatherRepository.isSourceStale()` ~line 178
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`

Revert the `date >= tomorrow` band-aid. With `weather_data.fetchedAt` no longer polluted by current temp fetches, the original `maxByOrNull { it.fetchedAt }` across all dates is correct again.

### E. Data retention cleanup
**File:** `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`

Add `currentTempDao.deleteOldData(cutoffTime)` in `cleanOldData()`.

## Update Tests (7 files)

| File | Change |
|------|--------|
| `WeatherRepositoryTest.kt` | Remove `currentTemp` from entity creation; revert `date >= tomorrow` test change |
| `WeatherRepositoryNwsParallelTest.kt` | Remove `currentTemp` references |
| `WeatherDaoTest.kt` | Remove `currentTemp` references |
| `ObservationResolverTest.kt` | Switch to `CurrentTempEntity` |
| `WeatherApiTest.kt` | Remove `currentTemp` from entity assertions |
| `OpenMeteoApiTest.kt` | Remove `currentTemp` from entity assertions |
| `DatabaseMigrationTest.kt` | Add migration 22→23 test |

## DI Wiring

**File:** `app/src/main/java/com/weatherwidget/di/DatabaseModule.kt`

Add `@Provides` for `CurrentTempDao` from `WeatherDatabase.currentTempDao()`.

## Verification

1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug` — build passes
2. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — unit tests pass
3. Install on Samsung + emulator, verify:
   - Current temp still displays on widget
   - Full forecast sync no longer blocked by current temp freshness
   - Pull DB and confirm `current_temp` table exists with today's data
   - `weather_data` no longer has `currentTemp`/`currentTempObservedAt` columns
