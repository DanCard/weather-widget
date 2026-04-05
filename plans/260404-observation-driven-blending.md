# Observation-Driven Blending Follow-Through

## Summary

The repo is already migrated to real observation timestamps for NWS blending and fetch-dot rendering. The remaining work is to finish the tail of that migration:

1. Preserve single-series behavior for non-NWS providers so side stations do not perturb the primary provider trace.
2. Update stale tests that still assume synthetic half-hour emissions the new design intentionally removed.

## Implementation

### 1. Keep observation-driven blending for NWS

`ObservationBlender.blendObservationSeries()` should remain the NWS path:

- candidate timestamps come only from real observation report times
- stations may contribute observed, interpolated, or forecast-guided extrapolated values at those real timestamps
- `resolveCurrentObservation()` continues to use the latest real observed anchor

### 2. Keep single-series behavior for non-NWS graph actuals

`TemperatureHourDataBuilder` should split source handling:

- `WeatherSource.NWS`: use the blended multi-station path
- non-NWS sources: choose one primary observation series with `selectObservationSeries(...)`, then build graph actuals from only that station's observations

This preserves the existing provider contract for sources like Open-Meteo while leaving the NWS observation-driven work intact.

### 3. Align tests with real timestamps plus carry-forward

Tests must no longer expect invented `10:30` / `11:30` emission rows. Instead they should verify:

- sub-hourly actuals are emitted only at real observation timestamps
- interpolation/extrapolation affects blends at those real timestamps
- top-of-hour buckets can still carry the last known actual forward for curve continuity
- the last observed anchor remains pinned to the freshest real observation, not a carried or extrapolated point

## Verification

1. Run `./gradlew testDebugUnitTest --tests 'com.weatherwidget.widget.handlers.TemperatureViewHandlerActualsTest'`
2. Run `./gradlew testDebugUnitTest`
3. Confirm:
   - non-NWS primary-series actuals are exact and unblended
   - NWS actuals still show observation-driven continuity
   - fetch-dot tests stay green with direct `lastObservedTemp` usage
