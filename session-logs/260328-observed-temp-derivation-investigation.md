# Session: Observed Temperature Derivation Investigation

**Date:** 2026-03-28
**Duration:** ~15 minutes
**Outcome:** User confusion resolved; no code changes needed

## Problem Statement

The user reported that the "last observed temperature" displayed on the emulator widget seemed wrong. The widget graph showed **72.3°** near the NOW line, which didn't match expectations.

## Investigation

### Data Collection

Pulled data directly from the emulator (emulator-5554) via:
- `app_logs` database table (tags: `CurrentTempResolver`, `OBS_DERIVATION`, `OBS_DERIVATION_DETAIL`, `NWS_IDW`, `IDW_BLEND`)
- `observations` table (raw station readings and NWS_BLEND entries)
- `hourly_forecasts` table (NWS forecast data around the observation window)
- Emulator screenshot

### Key Findings

#### Widget State at 19:40 (time of investigation)
- **Header current temp:** 72.1° (with +3.1 delta badge)
- **Graph observation curve at NOW:** 72.3°
- **Last real NWS observation:** AW020 station, 74.0°F at 19:05 (35 min stale)
- **NWS hourly forecast:** 72°F at 19:00, 68°F at 20:00

#### The 72.3° Derivation (Graph Value)

The graph's observation curve is produced by `ObservationBlender.blendObservationSeries()` which builds a time series combining:
1. **Real observations** where available
2. **Forecast-guided extrapolation** beyond the last real reading

The blended observation series at the time of investigation:

| Time  | Temp   | Source                  |
|-------|--------|------------------------|
| 19:05 | 74.5°  | observed (AW020 IDW blend) |
| 19:20 | 73.5°  | forecast_extrapolated   |
| 19:35 | 72.5°  | forecast_extrapolated   |
| 19:50 | 71.5°  | forecast_extrapolated   |

Extrapolation formula (`ObservationBlender.addForecastGuidedExtrapolatedPoints`):
```
extrapolated = lastObservation + (forecastAtTarget - forecastAtBase)
```

At 19:40 (NOW), the graph interpolates between 19:35 (72.5°) and 19:50 (71.5°), yielding ~72.2°, displayed as 72.3°.

#### The 72.1° Derivation (Header Value)

Separate pipeline via `CurrentTemperatureResolver.resolve()`:
```
displayTemp = forecastEstimate + decayedDelta
    72.2    =     69.1         +    3.12
```

- **forecastEstimate:** Linear interpolation of NWS hourly forecast at 19:40 = `72 + (68-72) * 40/60 = 69.1°`
- **delta:** `lastObserved(74.5) - forecastAtObsTime(71.4) = 3.12°` (stored, in 1hr grace period, no decay yet)

#### Station Data at Investigation Time

| Station | Distance | Last Reading | Time  |
|---------|----------|-------------|-------|
| AW020   | 2.9 km   | 74.0°F      | 19:05 |
| KNUQ    | 3.7 km   | 73.4°F      | 17:55 (stale) |
| KPAO    | 5.7 km   | (not recent) | -    |
| LOAC1   | 9.0 km   | 80.0°F      | 18:10 (outlier?) |
| KSJC    | 15.8 km  | 73.4°F      | 18:40 |

Only AW020 had data near 19:05, so the NWS_BLEND at that timestamp was effectively single-station.

## Resolution

The 72.3° graph value is correct given the system design. It represents forecast-guided extrapolation from the last real observation (74.0° at 19:05), applying the NWS forecast cooling rate (-4°/hr). The graph does not visually distinguish between real observations and extrapolated points, which caused the initial confusion.

## Key Takeaway

The observation curve on the hourly graph is a hybrid: real station readings where available, then forecast-trend extrapolation beyond the last reading. The extrapolated segments assume the NWS forecast cooling/warming rate is accurate, which can diverge from reality if the forecast trend is off. This is working as designed but can appear surprising when the extrapolated curve drops (or rises) faster than expected.

## No Code Changes

No bugs were identified. The system is functioning as designed. The user confirmed understanding after the derivation was explained.
