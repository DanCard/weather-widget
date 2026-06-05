# Plan to Fix Android Unit Tests

## Objective
Resolve the compilation errors across the Android test suite caused by the deduplication of the temperature actuals data model (`DailyExtreme`) and logic (`ObservationBlender`).

## Problem Categories
1. **Removed `ObservationBlender`:** Tests referencing `ObservationBlender.resolveCurrentObservation` or `ObservationBlender.blendObservationSeries` need to be updated to use the new shared `ActualsAggregator` and `ActualTemperatureSeriesBuilder`.
2. **Removed `DailyActual`:** Tests instantiating or referencing `ObservationResolver.DailyActual` need to be updated to use `com.weatherwidget.data.model.DailyExtreme`.
3. **Type Mismatch in `DailyExtreme`:** Some tests have a `DailyExtreme(date = today...)` where `today` is a `LocalDate`, but the new shared model expects a `Long` (UTC midnight epoch millis).

## Implementation Steps

### 1. Fix `ObservationBlender` References
Update the following tests to use `ActualsAggregator` or `ActualTemperatureSeriesBuilder` and map the entities using `.toReading()` and `.toHourlyForecast()`:
- `TemperaturePipelineBenchmark.kt`
- `TemperatureUnificationRegressionTest.kt`
- `TemperatureViewHandlerActualsTest.kt`

### 2. Fix `DailyExtreme` Instantiation & Imports
Many `DailyView` tests instantiate `DailyExtreme` improperly or still reference `DailyActual`. We will introduce a test helper function in these files to correctly construct a `DailyExtreme` from a `LocalDate`:

```kotlin
private fun extreme(date: LocalDate, high: Float, low: Float, condition: String = "Clear") = com.weatherwidget.data.model.DailyExtreme(
    date = date.toEpochDay() * 86_400_000L,
    source = com.weatherwidget.data.model.WeatherSource.NWS.id,
    locationLat = 0.0,
    locationLon = 0.0,
    highTemp = high,
    lowTemp = low,
    condition = condition,
    updatedAt = System.currentTimeMillis()
)
```

We will apply this helper and fix unresolved references in:
- `DailyViewHandlerTest.kt`
- `DailyViewLogicTest.kt`
- `DailyViewHandlerUnitTest.kt`
- `DailyViewHandlerTodayDropIntegrationTest.kt`
- `DailyViewUiRoundingTest.kt`
- `CurrentTempTouchRoutingRoboTest.kt`
- `TripleLinePrecisionTest.kt`
- `ObservationResolverTest.kt`
- `NwsHistoryIntegrationTest.kt`

### 3. Verify
Run `./gradlew test` to confirm all 1,300+ Android tests compile and pass.