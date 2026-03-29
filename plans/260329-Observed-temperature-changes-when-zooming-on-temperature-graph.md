# Fix: Observed temperature changes when zooming on temperature graph

## Context

Continuation of the current temp consistency fix. The "last observed temperature" (fetch dot value) on the temperature graph changes when zooming because:
- WIDE zoom uses 3 smoothing iterations, NARROW uses 1
- `buildHourDataResult` sets `HourData.temperature` from `smoothedForecasts` (line 1267)
- The fetch dot interpolation in `TemperatureGraphRenderer.computePoints` uses these temps as fallbacks when `actualTemperature` is null (line 405)
- Different smoothing → different fallback temps → different interpolated observation value

## Plan

**Use fixed `HEADER_SMOOTH_ITERATIONS` for all smoothing in TemperatureViewHandler**, not just the header. The graph still looks different at different zoom levels because the time window changes (WIDE=±12h, NARROW=±2h) — that's the real zoom effect. The smoothing iteration difference is subtle and causes inconsistency.

### File: `TemperatureViewHandler.kt`

Remove the dual-map approach (`smoothedForecasts` + `headerSmoothedForecasts`). Replace with a single map using fixed iterations:

```kotlin
val smoothedForecasts = computeSmoothedForecasts(hourlyForecasts, displaySource)
```

This uses the default `HEADER_SMOOTH_ITERATIONS = 3`. Both the graph and header use the same map. Replace all `headerSmoothedForecasts` references back to `smoothedForecasts`.

## Verification

1. `./gradlew testDebugUnitTest` — tests pass
2. On device: zoom in/out on temperature graph — fetch dot value and header temp stay the same
