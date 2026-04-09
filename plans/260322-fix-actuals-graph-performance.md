# Fix Actuals Graph Rendering Performance Bug

## Objective
Fix a severe performance regression (~8 seconds on emulators) when rendering the actual temperature line on the Hourly graph.

## Background & Motivation
The user reported that the widget was taking roughly 8 seconds to draw the actual temperature line on the emulator. An investigation into the logs and codebase revealed an inefficient loop in the logic that fills in missing weather data (Inverse Distance Weighting or IDW interpolation).

Currently, the code converts a timestamp from a text string into a date format over and over again. It does this for:
- Every weather station (e.g., 20 stations)
- Every 15-minute gap that needs to be filled (e.g., up to 12 gaps per station)
- Every single forecast point in our database (e.g., 200+ forecasts)

Because this calculation is nested inside multiple loops, `LocalDateTime.parse()` (which is very slow on Android) ends up being called tens of thousands of times every time the graph is drawn. This massive number of redundant text-to-date conversions is what causes the 8-second delay. 

## Scope & Impact
- **Impacted Area**: `TemperatureViewHandler.kt`, specifically `buildStationTimeSeries` and `forecastTemperatureAt`.
- **Symptoms**: `buildHourDataMs` block taking multiple seconds (e.g., 1.5s to 8s depending on CPU and station count).
- **Safety**: Safe. This is purely an internal optimization of data structures prior to interpolation, with zero functional behavior change.

## Proposed Solution

1. **Create Parsed Structure**:
   Introduce a lightweight data class to hold pre-parsed forecasts:
   ```kotlin
   private data class ParsedForecast(
       val timestampMs: Long,
       val temperature: Float
   )
   ```

2. **Refactor `hourlyForecastSeries`**:
   Update `hourlyForecastSeries` to return `List<ParsedForecast>` instead of `List<HourlyForecastEntity>`. Parse `LocalDateTime` strings to epoch milliseconds **once** during this mapping phase.

3. **Lift Evaluation Out of Loop**:
   In `buildStationTimeSeries`, move the `val forecastSeries = hourlyForecastSeries(...)` call **outside** of the `observations.groupBy { it.stationId }.mapValues { ... }` block so it is only computed once globally, not once per station.

4. **Optimize `forecastTemperatureAt`**:
   Update `forecastTemperatureAt` to accept `List<ParsedForecast>` and compare Long timestamps directly instead of calling `LocalDateTime.parse` in inner loops. Remove the `forecastDateTime` helper entirely.

## Alternative Considered
We could just cache `LocalDateTime.parse` results inside `forecastDateTime`, but that would still involve unnecessary map lookups inside tight loops and leave the O(N) string-based grouping inside the per-station loop. Pre-parsing the list into a `Long` based data structure is strictly better and simpler.

## Implementation Steps
1. Open `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`.
2. Add `private data class ParsedForecast(val timestampMs: Long, val temperature: Float)`.
3. Update `hourlyForecastSeries` to map the selected `HourlyForecastEntity` to `ParsedForecast` and sort by `timestampMs`.
4. Update `buildStationTimeSeries` to hoist `hourlyForecastSeries` before the `observations.groupBy` block.
5. Update `forecastTemperatureAt` signature and body to use `ParsedForecast` and integer math.
6. Verify no compile errors in `TemperatureViewHandler`.

## Verification & Testing
1. Run `./gradlew test` to ensure pure functions are still working.
2. Deploy to the emulator and trigger a refresh.
3. Check `logcat` for `TEMP_OBS_SLOW` or `TEMP_PIPELINE_PERF` to verify `buildHourDataMs` is now strictly in the single-digit or low double-digit milliseconds rather than thousands.
