# Split `weather_data` into Forecast + Current Temp Tables

**Date:** 2026-02-26
**Status:** Complete (build + unit tests passing)

## Problem

The `weather_data` table mixed daily forecast data with current temperature observations. The current temp fetch pipeline did `existing.copy(fetchedAt = now)` on today's row, which poisoned `fetchedAt` semantics. `isSourceStale()` saw a fresh `fetchedAt` from current temp updates and skipped full forecast refreshes — causing Samsung to show stale NWS forecast (74F) while the emulator had the updated value (76F).

## Solution

Separate current temperature observations into a dedicated `current_temp` table so that `weather_data.fetchedAt` only reflects actual forecast sync times.

## Changes

### New files (created during plan mode)
- `app/src/main/java/com/weatherwidget/data/local/CurrentTempEntity.kt` — `current_temp` table: PK `(date, source)`, fields: temperature, observedAt, condition, fetchedAt
- `app/src/main/java/com/weatherwidget/data/local/CurrentTempDao.kt` — CRUD operations + location-filtered queries

### Database (`WeatherDatabase.kt`)
- Migration 22->23: creates `current_temp` table, migrates existing data, recreates `weather_data` without `currentTemp`/`currentTempObservedAt` columns
- Version bumped to 23, entity + DAO registered

### Entity (`WeatherEntity.kt`)
- Removed `currentTemp: Float?` and `currentTempObservedAt: Long?` fields

### Writers (`WeatherRepository.kt`)
- **Core fix:** Current temp fetch pipeline now writes to `currentTempDao.insert()` instead of `weatherDao.insertWeather(existing.copy(currentTemp=..., fetchedAt=now))` — no longer touches `weather_data.fetchedAt`
- Open-Meteo and WeatherAPI full syncs write current temp to `currentTempDao` separately
- Dedup logic simplified (removed `currentTemp` comparison)
- `cleanOldData()` now includes `currentTempDao.deleteOldDataBySource()` with same retention policy

### Readers (5 production files)
- **`ObservationResolver.kt`** — accepts `List<CurrentTempEntity>` instead of `List<WeatherEntity>`
- **`DailyViewHandler.kt`** — uses `ObservationResolver` with new `currentTemps` parameter instead of inline WeatherEntity filtering
- **`WeatherWidgetProvider.kt`** — loads `currentTemps` from DB via `currentTempDao.getCurrentTemps()`, passes through `updateWidgetWithData()`
- **`WeatherWidgetWorker.kt`** — loads `currentTemps` at both `updateAllWidgets()` call sites
- **`WidgetIntentRouter.kt`** — loads `currentTemps` at all 6 DailyViewHandler call sites + hourly view update
- **`WeatherObservationsActivity.kt`** — queries `currentTempDao` for fallback current temp display

### Interface changes
- `WidgetViewHandler.updateWidget()` — added `currentTemps: List<CurrentTempEntity>` parameter (default `emptyList()`)
- `WeatherWidgetProvider.updateWidgetWithData()` — added `currentTemps` parameter
- `WeatherWidgetWorker.updateAllWidgets()` — added `currentTemps` parameter

### DI (`AppModule.kt`)
- Added `provideCurrentTempDao()` Dagger provider

### Tests (10 files updated)
| File | Change |
|------|--------|
| `WeatherRepositoryTest.kt` | Added `currentTempDao` mock; assertions verify `currentTempDao.insert()` |
| `WeatherRepositoryNwsParallelTest.kt` | Added `currentTempDao` mock; assertion checks `currentTempDao.insert()` |
| `WeatherRepositoryPoiTest.kt` | Added `currentTempDao` to 3 constructor calls |
| `WeatherRepositoryRateLimitIntegrationTest.kt` | Added `db.currentTempDao()` to constructor |
| `WeatherRepositoryMergeTest.kt` | Added `currentTempDao` mock |
| `WeatherGapTest.kt` | Removed `currentTemp` from helper; added `currentTempDao` mock |
| `ForecastSnapshotDeduplicationTest.kt` | Added `currentTempDao` mock |
| `NwsMiddayOverrideTest.kt` | Added `currentTempDao` mock |
| `WeatherHistoryConditionTest.kt` | Added `currentTempDao` mock |
| `ObservationResolverTest.kt` | Rewritten to use `CurrentTempEntity` |
| `DailyViewHandlerTest.kt` | Removed `currentTemp` from entity helper |
| `DailyActualsEstimatorTest.kt` | Removed `currentTemp` from entity construction |
| `WeatherDaoTest.kt` | Removed `currentTemp` from nullable test |
| `TestData.kt` | Removed `currentTemp` parameter from `weather()` factory |

## Verification
- `./gradlew assembleDebug` — BUILD SUCCESSFUL
- `./gradlew testDebugUnitTest` — BUILD SUCCESSFUL (all tests pass)
- Remaining: install on Samsung + emulator, verify current temp displays, confirm `current_temp` table exists with data, confirm `weather_data` no longer has dropped columns
