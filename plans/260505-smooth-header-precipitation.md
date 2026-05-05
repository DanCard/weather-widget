# Plan: Smooth Header Precipitation

## Summary
Replace the stepped header rain calculation with a smoothed rolling next-8-hours value. The header should keep meaning "highest chance of rain in the next 8 hours," but evaluate that from the exact current time instead of snapping to hourly bucket boundaries.

## Implementation
1. Update `HeaderPrecipCalculator` to evaluate the next 8 hours minute by minute.
2. Interpolate precipitation probability linearly between adjacent hourly forecast rows.
3. Keep source selection behavior the same: selected source first, `GENERIC_GAP` only if the selected source has no hourly rows.
4. Keep daily fallback behavior only when no usable hourly precip values are available.
5. Apply the shared helper everywhere it is already used so all header modes inherit the smoother value.

## Tests
1. Extend `HeaderPrecipCalculatorTest` with smoothing cases that prove the value no longer drops abruptly at the top of the hour.
2. Cover source fallback, daily fallback, null handling, and rolling-window behavior near the 8-hour edge.

## Assumptions
- Minute-by-minute evaluation is cheap enough for widget header rendering.
- Linear interpolation between hourly precip probabilities is acceptable for display smoothing.
- The new smoothed value should apply to all headers that use the shared precip helper.
