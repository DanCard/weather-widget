# Delete current_temp table — unify on observations

**Date**: 2026-03-19
**DB Version**: 36 → 37

## Problem

Tapping the current temperature on Samsung device toggles views (DAILY ↔ TEMPERATURE). The displayed temp jumps because:
- **DailyViewHandler** reads observed temp from `current_temp` table (e.g., 72.48°F)
- **TemperatureViewHandler** reads from `observations` table via IDW graph blending (e.g., 71.46°F)

Two tables storing observed temperatures = two sources of truth = inconsistency.

## Root Cause Analysis

The `current_temp` table was introduced in migration 22→23 to split current temp out of the `weather_data` table. It stored one row per (date, source) with the latest observed temperature.

Meanwhile, the `observations` table stores per-station, per-timestamp readings from all 4 API sources (NWS, Open-Meteo, WeatherAPI, Silurian). The `CurrentTempRepository` already wrote to **both** tables — `current_temp` for legacy reads and `observations` for graph blending.

Additionally, `ForecastRepository.getWeatherData()` had a callback (`onCurrentTempCallback`) that wrote to `current_temp` only when forecast API responses included current temp data. This was a secondary write path that further diverged the two tables.

## Solution

Delete the `current_temp` table entirely. All current temperature reads and writes go through the `observations` table using synthetic `_MAIN` station entries.

### Key Design Decisions

1. **Option A chosen over Option B**: Removed the `onCurrentTempCallback` entirely from `ForecastRepository` rather than migrating it to write observations. `CurrentTempRepository.refreshCurrentTemperature()` is the sole writer and runs frequently enough.

2. **`_MAIN` station convention**: Synthetic observations use stationId suffixed with `_MAIN` (e.g., `NWS_MAIN`, `OPEN_METEO_MAIN`, `WEATHER_API_MAIN`, `SILURIAN_MAIN`). The new DAO query `getLatestMainObservations()` uses `LIKE '%_MAIN'` to find them.

3. **`NWS_MAIN` blended observation**: After IDW spatial interpolation in `ObservationRepository.fetchNwsCurrent()`, the blended result is now stored as a synthetic `NWS_MAIN` observation. This ensures the same blended value is available to both view handlers.

4. **TemperatureViewHandler priority swap**: Changed `finalObsTemp` from `graphObservedTemp ?: observedCurrentTemp` to `observedCurrentTemp ?: graphObservedTemp`. Since both views now use the same observations table, the DB observation should take priority.

5. **`inferSource()` replaces `source` column**: The old `current_temp.source` field was a string like `"NWS"`. Now `ObservationResolver.inferSource(stationId)` maps stationId prefixes to source IDs, reusing existing logic from TemperatureViewHandler.

## Files Modified (13 source + 9 test)

### Core Changes

| File | Change |
|------|--------|
| `ObservationDao.kt` | Added `getLatestMainObservations()` query |
| `ObservationResolver.kt` | `resolveObservedCurrentTemp()` now accepts `List<ObservationEntity>` instead of `List<CurrentTempEntity>` |
| `ObservationRepository.kt` | Added NWS_MAIN synthetic observation after IDW blend; removed `currentTempDao` constructor param; removed current_temp sync block |
| `CurrentTempRepository.kt` | Removed `currentTempDao` constructor param and `currentTempDao.insert()` call |
| `WeatherRepository.kt` | Removed `currentTempDao` field and `onCurrentTempCallback` lambda |
| `ForecastRepository.kt` | Removed `onCurrentTempCallback` parameter from `getWeatherData()` and `fetchFromAllApis()`; removed all 4 callback invocations (NWS, Open-Meteo, WeatherAPI, Silurian) |

### Interface/Handler Changes

| File | Change |
|------|--------|
| `WidgetViewHandler.kt` | Interface `currentTemps` param type: `CurrentTempEntity` → `ObservationEntity` |
| `DailyViewHandler.kt` | All 4 overloads updated for `ObservationEntity` |
| `DailyViewLogic.kt` | Both `prepareTextDays()` and `prepareGraphDays()` updated |
| `TemperatureViewHandler.kt` | Swapped `finalObsTemp` priority |

### Read Site Changes (11 call sites)

| File | Sites | Change |
|------|-------|--------|
| `WidgetIntentRouter.kt` | 7 | `database.currentTempDao().getCurrentTemps(todayStr, lat, lon)` → `database.observationDao().getLatestMainObservations(lat, lon, todayStartMs)` |
| `WeatherWidgetProvider.kt` | 1 | Same pattern |
| `WeatherWidgetWorker.kt` | 2 | Same pattern |
| `WeatherObservationsActivity.kt` | 1 | Replaced `currentTempDao.getCurrentTemp()` fallback with `observationDao.getLatestMainObservations()` + `inferSource()` filter |

### Database Changes

| File | Change |
|------|--------|
| `WeatherDatabase.kt` | Removed `CurrentTempEntity` from `@Database` entities; removed `currentTempDao()` abstract fun; bumped version 36→37; added `MIGRATION_36_37` (DROP TABLE current_temp) |
| `AppModule.kt` | Removed `provideCurrentTempDao()` provider; removed `currentTempDao` from ObservationRepository, CurrentTempRepository DI |

### Deleted Files

- `CurrentTempEntity.kt`
- `CurrentTempDao.kt`

### Test Files Updated (9)

- `ObservationResolverTest.kt` — Rewrote test helpers to use `ObservationEntity` with `_MAIN` stationIds
- `DailyViewHandlerTest.kt` — Updated `CurrentTempEntity` → `ObservationEntity` in 3 tests
- `WeatherRepositoryTest.kt` — Removed `currentTempDao` mock
- `WeatherRepositoryNwsParallelTest.kt` — Removed `currentTempDao` mock
- `WeatherGapIntegrationTest.kt` — Removed `db.currentTempDao()` from constructors
- `WeatherRepositoryRateLimitIntegrationTest.kt` — Same
- `ForecastSnapshotDeduplicationTest.kt` — Same
- `WeatherGapTest.kt` — Removed `currentTempDao` mock arg
- `WeatherRepositoryPoiTest.kt` — Same
- `NwsMiddayOverrideTest.kt` — Same

## Migration Details

**Migration 36→37**: Simply drops the `current_temp` table. No data migration needed because:
- The `observations` table already has all the data (CurrentTempRepository wrote to both tables)
- `_MAIN` observations will be populated on the next refresh cycle
- Worst case: first widget render after upgrade may show interpolated temp instead of observed until the next `CurrentTempRepository.refreshCurrentTemperature()` runs

## Data Flow (After)

```
CurrentTempRepository.refreshCurrentTemperature()
  └── fetchFromSource(NWS) → ObservationRepository.fetchNwsCurrent()
  │     ├── Per-station observations → observationDao.insertAll()  (e.g., KSFO, KSJC)
  │     └── NWS_MAIN blended observation → observationDao.insertAll()  ← NEW
  └── fetchFromSource(OPEN_METEO) → fetchOpenMeteoCurrent()
  │     └── OPEN_METEO_MAIN observation → observationDao.insertAll()
  └── fetchFromSource(WEATHER_API) → fetchWeatherApiCurrent()
  │     └── WEATHER_API_MAIN observation → observationDao.insertAll()
  └── fetchFromSource(SILURIAN) → fetchSilurianCurrent()
        └── SILURIAN_MAIN observation → observationDao.insertAll()

Widget render (both DAILY and TEMPERATURE views):
  observationDao.getLatestMainObservations(lat, lon, todayStartMs)
    └── ObservationResolver.resolveObservedCurrentTemp(observations, displaySource)
          └── inferSource(stationId) matches against displaySource
```

## Verification

- [x] `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- [x] `./gradlew testDebugUnitTest` — all tests pass
- [x] `./gradlew installDebug` — installed on Samsung + emulator
- [ ] On Samsung: toggle DAILY ↔ TEMPERATURE — verify temps match
- [ ] Check logs: `adb logcat | grep "headerState"` — `currentTemp=` should match between modes
- [ ] Verify `observations` table has `NWS_MAIN` entries after a refresh cycle

## Risk Assessment

**Low risk**: The `observations` table already stored all the data. We're just removing the redundant copy. The only gap is the first render after migration before a refresh — which shows interpolated temp (existing fallback behavior).

**Removed callback concern**: `ForecastRepository` no longer writes current temps. This is safe because `CurrentTempRepository.refreshCurrentTemperature()` runs independently and frequently (every 5 minutes when conditions allow). The forecast callback was a bonus data path, not the primary one.
