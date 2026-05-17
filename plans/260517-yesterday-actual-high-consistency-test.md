# Plan: Yesterday Actual High Consistency Test

## Objective
Implement a Robolectric test to verify that the "actual high" temperature for yesterday is consistent between the Daily Forecast view and the Hourly Temperature graph. This ensures that the pre-computed persisted data (Daily) and the runtime-blended data (Hourly) align perfectly.

## Strategy
I will perform an integrated test using an in-memory database and real repository logic (with mocked network APIs). This avoids the flakiness of mocks for data logic and tests the actual production code paths.

## Implementation Details

### 1. Dependency Satisfaction
`WeatherRepository` has a complex constructor. I will manually assemble its dependencies:
- **DAOs**: Obtained from `TestDatabase.create()`.
- **APIs**: Mocked using `mockk` (`NwsApi`, `OpenMeteoApi`, `WeatherApi`, `SilurianApi`, etc.).
- **Repositories**:
    - `ForecastRepository`: Needs DAOs, APIs, and `WidgetStateManager`.
    - `CurrentTempRepository`: Needs DAOs, APIs, `WidgetStateManager`, and `TemperatureInterpolator`.
    - `ObservationRepository`: Needs DAOs and `NwsApi`.
- **Context**: `ApplicationProvider.getApplicationContext()`.

### 2. Test Execution Path
1. **Initialize Environment**: Setup all dependencies and repositories.
2. **Inject Test Data**:
    - Observations for yesterday with a clear peak (e.g., 78.5°F at 3 PM).
    - Hourly forecasts covering the period (needed for IDW blending).
3. **Trigger Recomputation**: Call `repository.recomputeDailyExtremesFromStoredObservations` for yesterday.
4. **Gather Daily Data**: Call `DailyViewLogic.prepareGraphDays` using the correctly mapped parameters.
5. **Gather Hourly Data**: Call `buildHourDataList` (from `TemperatureHourDataBuilder.kt`) centered on yesterday's peak.
6. **Assert Consistency**:
    - Compare `yesterdayData.solidLineHigh` from Daily view with `hourlyData.maxOf { it.actualTemperature }` from Hourly view.
    - Assert both are 78.5°F.

## Key Seams & Functions
- `ObservationRepository.recomputeDailyExtremesFromStoredObservations`
- `DailyViewLogic.prepareGraphDays`
- `com.weatherwidget.widget.handlers.buildHourDataList` (Top-level function in `TemperatureHourDataBuilder.kt`)

## Verification Plan
1. **Build**: Ensure the test compiles by correctly satisfying all constructor and function parameters.
2. **Run**: Execute `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.YesterdayActualHighConsistencyTest`.
3. **Verify**: Ensure the test passes and correctly identifies the 78.5°F peak in both views.
