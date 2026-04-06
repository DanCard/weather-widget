# Fix Forecast Low Label Missing Plan

## Objective
Fix the issue where the "low for the day for the forecast" is not labeled. The root cause is that `forecastLowIndex` (and `forecastHighIndex`) are being computed across the *entire* array of hours (indices 0 to 151), even though the forecast line only draws on the right side of the graph (e.g. indices 50 to 151). If the absolute lowest forecast value occurs in the past, the forecast line doesn't get a low label.

## Changes

1. **Restrict Forecast Extrema to the Forecast Window:**
   In `TemperatureGraphRenderer.kt`'s `placeTemperatureLabels` method:
   - Compute `forecastStartIndex` as `if (ctx.transitionX != null) ctx.effectiveActualEndIndex else 0`.
   - Compute `forecastEndIndex` as `hours.lastIndex`.
   - Define `forecastIndices = (forecastStartIndex..forecastEndIndex).filter { it in forecastLabelTemps.indices }`.
   - Compute `forecastHighIndex` and `forecastLowIndex` by searching only within `forecastIndices`.

2. **Refactor Extrema Calculation Block:**
   Reorder the definitions so `actualStartIndex`/`actualEndIndex` and `forecastStartIndex`/`forecastEndIndex` are clearly defined and used to bounds-check the respective extrema.

## Verification
- Unit tests: Run the `com.weatherwidget.widget.*` suite to verify no regressions in label placement.
- Visual check: Verify on the emulator that the lowest point on the dashed forecast curve is correctly labeled.
