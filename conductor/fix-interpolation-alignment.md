# Fix Temporal Mismatch in Temperature Delta Calculation

## Objective
The current temperature interpolation logic calculates the "Delta" (the difference between reality and forecast) by comparing a station's reported temperature (e.g., from 30 minutes ago) against the *current* forecast estimate. This causes the delta to incorrectly include forecast trends that happened between the observation and the fetch.

We need to align the forecast estimate with the observation's reported timestamp during delta calculation.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt`
- `app/src/test/java/com/weatherwidget/widget/CurrentTemperatureResolverTest.kt`

## Implementation Steps

### 1. Update `CurrentTemperatureResolver.resolve`
- Within the block that handles new observations:
    - Convert `observedAt` (Epoch Millis) to `LocalDateTime`.
    - Use `interpolator.getInterpolatedTemperature` to get the forecast estimate at that specific past time.
    - Calculate `delta = observedCurrentTemp - estimatedAtObservationTime`.
- This ensures the delta purely represents the forecast error at the moment of observation.

### 2. Verify and Update Unit Tests
- Add a test case to `CurrentTemperatureResolverTest` where:
    - Forecast at T+0 is 80°.
    - Forecast at T+60 is 70° (cooling trend).
    - Observation at T+30 is 72°.
    - The code should calculate the forecast at T+30 (75°) and produce a delta of -3° ($72 - 75$).
    - Previously, it might have compared 72° against the current time (e.g., T+45) and produced a different delta.

## Verification
- Run `./gradlew test`
- Build and deploy to emulator.
- Observe logs for `CurrentTempResolver` to confirm delta calculations are time-aligned.
