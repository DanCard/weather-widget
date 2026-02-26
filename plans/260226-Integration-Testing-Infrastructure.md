# Plan: Integration Testing Infrastructure

## Context
The codebase has 39 unit tests and 25 instrumented tests but no **middle-layer integration tests** that exercise real Room DB queries through the Repository. Currently, unit tests mock DAOs entirely (losing query logic coverage) and instrumented tests require a device. This plan adds Room in-memory DB tests under Robolectric — fast, no emulator needed.

## Phase 1: Foundation

### 1a. Add gradle dependencies
- **`gradle/libs.versions.toml`**: Add `room-testing` library entry (uses existing `room` version ref)
- **`app/build.gradle.kts`**: Add `testImplementation(libs.room.testing)`

### 1b. Create test utilities (2 new files)
- **`app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt`** — `Room.inMemoryDatabaseBuilder()` + `allowMainThreadQueries()`
- **`app/src/test/java/com/weatherwidget/testutil/TestData.kt`** — Factory methods for `WeatherEntity`, `ForecastSnapshotEntity`, `HourlyForecastEntity` with sensible defaults

### 1c. Smoke test
- **`app/src/test/java/com/weatherwidget/data/local/WeatherDaoTest.kt`** — Insert/retrieve, composite key behavior
- Run to verify Robolectric + Room in-memory setup works

## Phase 2: DAO Integration Tests (2 new files)

- **`ForecastSnapshotDaoTest.kt`** — Composite key allows multiple snapshots per target date; `getForecastsInRange` window correctness
- **`HourlyForecastDaoTest.kt`** — Query window correctness with `getHourlyForecasts`; multi-source composite key

## Phase 3: Repository Integration Tests (3 new files)

All use real in-memory Room DB + MockK for HTTP APIs only.

- **`WeatherRepositoryMergeTest.kt`** — Historical actuals survive forecast fetch; forecast-only records get updated. Tests go through `getWeatherData()` (since `mergeWithExisting` is private).
- **`WeatherRepositoryStationFallbackTest.kt`** — Mock station 1 returning null, station 2 returning data; verify correct `stationId` saved in DB
- **`WeatherRepositoryRateLimitIntegrationTest.kt`** — Rate limit resets to 0 on failure; preserved on success (real SharedPreferences)

## Phase 4: Scenario Tests (2 new files)

- **`RainAnalyzerQueryWindowTest.kt`** — Verify 60h lookahead window covers 2-day-out rain; narrow ±3h window misses it (regression guard)
- **`ForecastSnapshotDeduplicationTest.kt`** — Identical forecast skips snapshot; changed forecast saves new snapshot

## Phase 5: Migration Tests (1 new instrumented test file)

- **`app/src/androidTest/java/com/weatherwidget/data/local/DatabaseMigrationTest.kt`** — Full v1→v19 chain + individual recent migrations (v17→18, v18→19). Uses `MigrationTestHelper` (requires instrumentation).

## Key Corrections from Exploration
- DAO method is `getHourlyForecasts` (not `getHourlyForecastRange`)
- No `getForecastsForDate` plural — use `getForecastsInRange` or `getForecastForDate` (singular)
- `mergeWithExisting` and `saveForecastSnapshot` are **private** — test through `getWeatherData()`
- `NwsApi.Observation` has `temperatureCelsius` (Float), not `temperature`
- Repository constructor has 11 params (Hilt `@Inject`) — must construct manually in tests

## Files Modified
- `gradle/libs.versions.toml` — 1 line added
- `app/build.gradle.kts` — 1 line added

## Files Created (11 total)
1. `app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt`
2. `app/src/test/java/com/weatherwidget/testutil/TestData.kt`
3. `app/src/test/java/com/weatherwidget/data/local/WeatherDaoTest.kt`
4. `app/src/test/java/com/weatherwidget/data/local/ForecastSnapshotDaoTest.kt`
5. `app/src/test/java/com/weatherwidget/data/local/HourlyForecastDaoTest.kt`
6. `app/src/test/java/com/weatherwidget/data/repository/WeatherRepositoryMergeTest.kt`
7. `app/src/test/java/com/weatherwidget/data/repository/WeatherRepositoryStationFallbackTest.kt`
8. `app/src/test/java/com/weatherwidget/data/repository/WeatherRepositoryRateLimitIntegrationTest.kt`
9. `app/src/test/java/com/weatherwidget/util/RainAnalyzerQueryWindowTest.kt`
10. `app/src/test/java/com/weatherwidget/data/repository/ForecastSnapshotDeduplicationTest.kt`
11. `app/src/androidTest/java/com/weatherwidget/data/local/DatabaseMigrationTest.kt`

## Verification
```bash
# Phase 1 smoke test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "com.weatherwidget.data.local.WeatherDaoTest"

# All new unit tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "com.weatherwidget.data.local.*DaoTest" --tests "com.weatherwidget.data.repository.*MergeTest" --tests "com.weatherwidget.data.repository.*FallbackTest" --tests "com.weatherwidget.data.repository.*RateLimitIntegrationTest" --tests "com.weatherwidget.data.repository.*DeduplicationTest" --tests "com.weatherwidget.util.RainAnalyzerQueryWindowTest"

# Migration tests (emulator only)
./scripts/run-emulator-tests.sh -c com.weatherwidget.data.local.DatabaseMigrationTest
```
