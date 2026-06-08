# Plan: Desktop Backfill Integration Test

## Objective
Create an integration test in the desktop module to verify the one-time historical backfill logic. The test must ensure that a fresh database triggers a backfill from `DesktopWeatherService.fetchHistory()`, saves it as `GENERIC_GAP`, and correctly stitches the backfilled cloud cover into the primary display source (e.g., NWS) upon loading.

## Target File
`desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopBackfillIntegrationTest.kt`

## Test Setup Architecture
- **In-Memory Database**: Use an in-memory SQLite database (`jdbc:sqlite::memory:`) configured via `DesktopWeatherDatabase` to ensure a clean state for each test.
- **Mock Service**: Use `MockK` to mock `DesktopWeatherService`.
  - `fetchForecast()`: Returns a mocked `ForecastResult` simulating a standard NWS response where historical/current hourly rows exist but have `cloudCover = null`.
  - `fetchHistory(3)`: Returns a mocked `ForecastResult` simulating an Open-Meteo response with 72 hours of data where `cloudCover` is populated (e.g., `85%`).

## Test Cases

### 1. `fresh install triggers backfill and stitches gaps`
- **Precondition**: An empty database.
- **Action**: Call `repository.refresh()`, then `repository.loadCached()`.
- **Expected Behavior**:
  - `weatherService.fetchHistory(3)` is called exactly once.
  - `weatherService.fetchForecast()` is called.
  - The returned `ForecastResult` from `loadCached()` contains hourly rows where `cloudCover` matches the backfilled value (85) for hours that were null in the primary forecast.
  - Database verification: Querying `hourly_forecast_history` for `source = "Generic"` returns the backfilled rows.

### 2. `populated history skips backfill`
- **Precondition**: The database is pre-seeded with > 24 hours of hourly history.
- **Action**: Call `repository.refresh()`.
- **Expected Behavior**:
  - `weatherService.fetchHistory(3)` is **never** called.
  - `weatherService.fetchForecast()` is called.

### 3. `backfill occurs only once per session`
- **Precondition**: An empty database.
- **Action**: Call `repository.refresh()` twice sequentially.
- **Expected Behavior**:
  - `weatherService.fetchHistory(3)` is called exactly **once** (on the first refresh).
  - The internal `hasAttemptedBackfill` flag prevents subsequent calls during the second refresh, even if the backfill failed or returned sparse data.

## Implementation Steps
1. Create the `DesktopBackfillIntegrationTest.kt` file.
2. Setup the JUnit `@Before` block to initialize the DB and `DesktopWeatherDao`.
3. Create helper functions to generate dummy `HourlyForecast` lists.
4. Implement the three test cases using `coVerify` from MockK to assert service calls and standard JUnit assertions to verify the stitched `cloudCover` values.
5. Run the test via `./gradlew :desktop:test --tests "*DesktopBackfillIntegrationTest*"` to confirm passing status.
