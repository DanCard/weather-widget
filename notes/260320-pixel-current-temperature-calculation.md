# Current Temperature Calculation — Pixel 7 Pro Live Example

Captured from Pixel 7 Pro logs on 2026-03-20 ~7:39 PM Pacific.

## How the current temp is calculated

The widget header shows a "current temperature" that combines two data sources:
an **hourly forecast interpolation** (what the forecast says right now) and a
**recent observation** (what stations actually measured). A **delta** corrects
the forecast toward reality, and **decays** over time as the observation ages.

### Step 1: Observed Temperature (from observations DB)

```
getMainObservationsWithComputedNwsBlend: nwsStationObsAll=3263 sinceMs=1773990000000
after filtering by sinceMs: 469
dedupedNwsObs=20
blendedTemp=79.7 newestTimestamp=1774058100000
```

- 3,263 total NWS station observations in DB
- 469 from today (after `sinceMs` filter)
- 20 unique stations after dedup by `stationId`
- IDW (inverse distance weighted) interpolation → **observedTemp = 79.7°F**
- Newest observation timestamp: 1774058100000 → ~12:15 PM

### Step 2: Estimated Temperature (from hourly forecast interpolation)

```
resolve:start now=2026-03-20T19:38:57 source=NWS hourlyCount=28
estimatedTemp=76.3
```

- 28 hourly forecasts from NWS loaded from DB
- `TemperatureInterpolator` does linear interpolation between the two
  bracketing hours
- **estimatedTemp = 76.3°F** (at 7:38 PM)

### Step 3: Stored Delta (persisted from previous observation fetch)

```
storedDelta=delta=-0.80000305 observed=79.7 fetchedAt=1774058100000
updatedAt=1774058100000 source=NWS lat=37.422 lon=-122.0841 scopeMatch=true
```

- Previously stored delta: **-0.80°F**
- This delta was computed when the observation at 79.7°F was fetched:
  `delta = observedTemp - estimatedAtObsTime = 79.7 - (estimated at ~12:15 PM) ≈ -0.8`
- The observation was warmer than the hourly forecast predicted at that moment,
  so delta is negative (the forecast is too high relative to what's actually
  happening)

### Step 4: Delta Decay

```
delta raw=-0.80000305, decayed=-0.6582044, elapsedMs=2552366, decayPercent=82.27524
```

- 2,552,366 ms elapsed since delta was set → **~42.5 minutes**
- Decay window: 4 hours (14,400,000 ms)
- Remaining fraction: `1 - (2552366 / 14400000) = 0.8228`
- Decayed delta: `-0.80 * 0.8228 = **-0.658**`
- Delta linearly decays to zero over 4 hours

### Step 5: Display Temperature

```
resolve:result displayTemp=75.6418 estimatedTemp=76.3 observedTemp=79.7
appliedDelta=-0.6582044 isStaleEstimate=false
```

- `displayTemp = estimatedTemp + appliedDelta`
- `displayTemp = 76.3 + (-0.658) = **75.64°F**`

### Step 6: Staleness Check

```
isStaleHourlyData: source=NWS scopedCount=121 latestFetchMs=1774058023893
ageMs=2628473 thresholdMs=7200000 stale=false
```

- 121 NWS hourly forecasts scoped
- Latest fetch: ~43 minutes ago
- Threshold: 2 hours → **not stale**

## Summary

| Component | Value | Source |
|---|---|---|
| Estimated temp (interpolated from hourly) | 76.3°F | Hourly forecast interpolation at current time |
| Observed temp (IDW blended from stations) | 79.7°F | 20 NWS stations, IDW interpolation |
| Stored delta | -0.80°F | `observed - estimated_at_obs_time` |
| Decayed delta (after 42 min) | -0.66°F | Linear decay over 4-hour window |
| **Display temp** | **75.6°F** | `estimated + decayed_delta` |

## Code References

- **Resolver**: `CurrentTemperatureResolver.resolve()` — `CurrentTemperatureResolver.kt:59`
- **Delta state**: `CurrentTemperatureDeltaState` — persisted per widget+source in `WidgetStateManager`
- **Interpolation**: `TemperatureInterpolator.getInterpolatedTemperature()`
- **Observed temp**: `ObservationResolver.resolveObservedCurrentTemp()` — `ObservationResolver.kt:35`
- **NWS blend**: `ObservationRepository.getMainObservationsWithComputedNwsBlend()` — `ObservationRepository.kt:382`
- **Widget handler**: `DailyViewHandler.updateDailyView()` — `DailyViewHandler.kt:214`
