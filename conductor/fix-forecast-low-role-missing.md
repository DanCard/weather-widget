# Fix Forecast Low Missing Plan

## Objective
Fix the issue where the forecast low (or high) label is missing. The root cause is that if the absolute minimum of the forecast falls exactly on the last point of the graph (which is very common), it is assigned the role `END` instead of `FORECAST_LOW`. The `END` role has lower priority and is actively suppressed if there are other labels nearby (`NEARBY_ENDPOINT_CLUTTER`), causing the forecast low to disappear completely from the screen.

## Changes

1. **Reorder Role Priority:**
   In `TemperatureGraphRenderer.kt` (`placeTemperatureLabels`), move the checks for `forecastHighIndex` and `forecastLowIndex` to evaluate *before* the checks for `0` (START) and `hours.lastIndex` (END).

   ```kotlin
            var role = when (idx) {
                dailyHighIndex -> "HIGH"
                dailyLowIndex -> "LOW"
                actualHighIndex -> "ACTUAL_HIGH"
                actualLowIndex -> "ACTUAL_LOW"
                forecastHighIndex -> "FORECAST_HIGH"
                forecastLowIndex -> "FORECAST_LOW"
                0 -> "START"
                hours.lastIndex -> "END"
                else -> "LOCAL"
            }
   ```

2. **Why this works:**
   If the `FORECAST_LOW` is at the end of the graph, it will now correctly be identified as a `FORECAST_LOW`. Because `FORECAST_LOW` is an "essential" role, it will force itself to be placed (and will not trigger the `NEARBY_ENDPOINT_CLUTTER` check, which specifically targets the `END` role).

## Verification
- Run widget unit tests to ensure label placement regressions haven't occurred.
- Visual inspection on the emulator will show that the forecast low is always labeled, even when it rests at the far right edge of the graph.
