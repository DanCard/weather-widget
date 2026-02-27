# NWS Observation Error Analysis - Feb 26, 2026

## Summary

The last NWS data fetch for Feb 26, 2026 on Samsung SM-F936U1 (22:52:20) resulted in an erroneous high temperature (75.992°F) due to an anomalous observation reading from station AW020. The forecast prediction itself was reasonable (71°F), but an erroneous observation overrode it.

## Data Sources Clarification

NWS provides two distinct data types:
1. **Forecast periods**: 14-day predicted temperatures (from `nwsApi.getForecast()`)
2. **Observations**: Actual measured temperatures from weather stations (from `nwsApi.getObservations()`)

The code fetches observations to get actual high/low for historical days and sometimes uses them to override forecast values.

## Evidence

### Final stored value (22:52:20)
- **High: 75.992°F** - erroneous observation from AW020
- **Low: 53°F** - from overnight forecast period "Tonight"
- **Condition: Mostly Cloudy**
- **Source: high=OBS:AW020, low=FCST:Tonight**

### Previous stored value (05:40:56)
- High: 71°F
- Low: 52°F
- Condition: Areas Of Fog

### Actual observations from all stations on Feb 26, 2026
These are the highest measured temperatures reported:
- KPAO: 73.4°F (16:47)
- AW020: 71.0°F (17:55)
- LOAC1: 71.0°F (17:10)
- WeatherAPI stations: 70.3°F (18:00)
- KNUQ: 69.8°F (17:35)

### NWS Forecast predictions for Feb 26
The NWS forecast periods (predictions) were reasonable:
- Daytime forecast: High 71°F
- Overnight "Tonight" forecast: Low 53°F
- Hourly forecasts: Max 71°F

### The Anomaly
The NWS API returned an erroneous observation from station AW020 with a temperature of approximately 24.44°C. When converted to Fahrenheit:
- 24.44°C × 1.8 + 32 = 75.992°F

This anomalous reading:
- Was returned by `nwsApi.getObservations()` during the 22:52:20 fetch
- Has excessive decimal precision (3 decimal places) that is characteristic of calculation errors, not real measurements
- Is ~5°F higher than the next highest observation (KPAO at 73.4°F)

## Root Cause

The error originates in `ForecastRepository.kt:188` in the `fetchDayObservations` function:

```kotlin
val ts = obs.map { (it.temperatureCelsius * 1.8f) + 32f }
val h = ts.maxOrNull() ?: continue
```

### How the error occurred:
1. `fetchDayObservations` calls `nwsApi.getObservations()` for AW020
2. NWS API returned an erroneous observation reading of ~24.44°C for Feb 26
3. Code converts to Fahrenheit: (24.44 × 1.8) + 32 = 75.992°F
4. This observation value overrode the forecast prediction of 71°F for the daily high
5. The anomalous observation is not persisted to `weather_observations` table - only used to populate high/low in `weather_data`

### Why the observation is suspicious:
- Excessive decimal precision (3 places) typical of calculation errors
- Significantly exceeds hourly forecast max (71°F)
- Significantly exceeds all other station observations (max 73.4°F)
- The AW020 station's own observations in `weather_observations` table show max 71.0°F (17:55), not 75.992°F

## Inference

The code accepts NWS observation readings without validation:
- No comparison against hourly forecast predictions for the same date
- No range/sanity checks on temperature values
- No cross-reference with nearby station observations
- No detection of anomalous decimal precision patterns

This allows a single erroneous observation reading to override reasonable forecast predictions and corrupt the stored daily high temperature.

## Recommendations

1. Add validation to `fetchDayObservations`:
   - Compare observation-based high against hourly forecast max for the same date
   - Reject values that deviate significantly (>5-10°F) from forecast predictions
   - Cross-reference with nearby station observations
   - Flag temperatures with excessive decimal precision (>1 decimal place)

2. Add diagnostic logging:
   - Log raw observation temperature in Celsius before conversion
   - Log the max observation value being used
   - Log when an observation overrides a forecast value

3. Improve robustness:
   - Consider using median of multiple nearby stations instead of single station max
   - Apply sanity bounds (e.g., -20°F to 120°F for Bay Area)
   - Allow manual rejection of anomalous stations
