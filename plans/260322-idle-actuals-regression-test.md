# Add Idle-Period Actual-Line Regression Test

## Summary
Add an integration-style regression test for the slow hourly actual-line path after the widget has been idle for a while. The test should exercise the NWS blending path with sparse multi-station observations, assert that synthetic-point growth stays bounded, and also enforce a loose runtime budget so large regressions are caught without depending on emulator rendering.

## Key Changes
- Extend `TemperatureViewHandlerActualsTest` with a realistic idle-period NWS fixture:
  - fixed noon center time
  - WIDE graph window
  - 3-5 stations with sparse, staggered observations
  - at least one station that triggers interpolation and one that triggers forecast-guided extrapolation
- Add one primary regression test around `TemperatureViewHandler.blendObservationSeries(...)`:
  - measure elapsed time with a monotonic clock
  - assert `BlendObservationStats` stays within explicit bounds for candidate timestamps, emitted points, and per-station synthetic expansion
  - assert the scenario actually exercised both interpolation and extrapolation
- Add one end-to-end check through `buildHourDataList(...)` using the same fixture so the graph-facing path still produces visible actuals.

## Test Plan
- Structural assertions:
  - bounded `candidateTimeCount`
  - bounded `emittedPointCount`
  - bounded per-station `interpolatedPointCount + extrapolatedPointCount`
  - at least one station with interpolation and at least one with extrapolation
- Runtime assertion:
  - one generous elapsed-time threshold that catches obvious regressions but is tolerant of unit-test variance
  - assertion message includes measured elapsed ms and stats summary
- Validation:
  - run targeted `TemperatureViewHandler` handler tests
  - run full `./gradlew test`

## Assumptions
- The highest-risk idle-period slowdown is in the NWS actual blending path, so v1 coverage stays NWS-only.
- Structural bounds are the primary signal; the timing threshold is intentionally loose and only meant to catch order-of-magnitude regressions.
