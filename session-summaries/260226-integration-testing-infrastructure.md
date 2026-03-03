# Integration Testing Infrastructure

**Date:** 2026-02-26

## What Changed

Added a middle-layer integration test suite that exercises real Room DB queries through Robolectric — fast, no emulator needed. Also made `mergeWithExisting` and `saveForecastSnapshot` `internal` (with `@VisibleForTesting`) for direct testability.

## Files Modified (3)

- `gradle/libs.versions.toml` — added `room-testing` library entry
- `app/build.gradle.kts` — added `testImplementation` and `androidTestImplementation` for room-testing
- `WeatherRepository.kt` — changed `mergeWithExisting` and `saveForecastSnapshot` from `private` to `internal` with `@VisibleForTesting`

## Files Created (11)

| File | Tests | Purpose |
|------|-------|---------|
| `testutil/TestDatabase.kt` | — | Room in-memory DB factory |
| `testutil/TestData.kt` | — | Entity factories with defaults |
| `WeatherDaoTest.kt` | 8 | Insert/retrieve, composite keys, proximity, cleanup |
| `ForecastSnapshotDaoTest.kt` | 6 | Composite key, range queries, evolution |
| `HourlyForecastDaoTest.kt` | 6 | Time window queries, multi-source, precip |
| `WeatherRepositoryMergeTest.kt` | 6 | Actual preservation, null fill, dedup, placeholder |
| `WeatherRepositoryStationFallbackTest.kt` | 4 | Station ID persistence and fallback |
| `WeatherRepositoryRateLimitIntegrationTest.kt` | 3 | Rate limit reset on failure (real SharedPrefs) |
| `ForecastSnapshotDeduplicationTest.kt` | 7 | Identical skip, changed save, exclusions |
| `RainAnalyzerQueryWindowTest.kt` | 2 | 60h window regression guard |
| `DatabaseMigrationTest.kt` | 10 | v9-19 chain + individual step migrations |

## Test Counts

- **Unit tests:** 324 pass (up from ~282)
- **Migration tests:** compile-ready for emulator via `./scripts/run-emulator-tests.sh -c com.weatherwidget.data.local.DatabaseMigrationTest`

## Key Design Decisions

- **`internal` over `public`**: `mergeWithExisting` and `saveForecastSnapshot` are now `internal` with `@VisibleForTesting` — testable from same-module tests without leaking to external consumers.
- **Robolectric + Room in-memory**: All new DAO and repository integration tests run in ~5s alongside existing unit tests. No emulator needed.
- **Migration tests are instrumented**: `MigrationTestHelper` requires `Instrumentation`, so `DatabaseMigrationTest.kt` lives in `androidTest/` and runs on emulator only.

## Verification

```bash
# All unit tests (including new integration tests)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest

# Migration tests (emulator only)
./scripts/run-emulator-tests.sh -c com.weatherwidget.data.local.DatabaseMigrationTest
```
