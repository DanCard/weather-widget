# Temperature History Interpolation Implementation

This note explains how the temperature-history interpolation change works in the hourly graph path.

## Summary

Implementation `16` is not generic smoothing of the final rendered line. It does station-local interpolation first, then blends those station-local points into the graph series.

The practical goal is to reduce jagged jumps caused by intermittent station reporting, such as `KNUQ` appearing in one window and disappearing in the next.

## Entry Point

The flow starts in `TemperatureViewHandler.buildHourDataList(...)`.

File:
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`

That method:
- filters observations for the selected source
- logs a summary of the stations present in the visible window
- calls `blendObservationSeries(...)`
- injects the blended actual-history points into the hourly graph data

## High-Level Algorithm

`blendObservationSeries(...)` now works like this:

1. Filter observations to the selected source and visible time window.
2. Build one local time series per station with `buildStationTimeSeries(...)`.
3. Insert short-gap interpolated points into those station-local series when appropriate.
4. For NWS station blending, briefly extrapolate forward after a station’s last observation using the forecast trend.
5. Gather all timestamps from all station-local series, including interpolated and extrapolated ones.
6. For each candidate timestamp, ask every station for its nearest local point within the allowed window.
7. If only one station contributes, emit that station’s temperature directly.
8. If multiple stations contribute, run inverse-distance weighting (IDW) across those station-local points.
9. Emit a blended observation for that timestamp and log the full cohort details.

## Station-Local Interpolation

`buildStationTimeSeries(...)` groups observations by `stationId` and sorts each station’s observations by timestamp.

For each pair of consecutive observations in a station:

- If the gap is `<= 15 minutes`, no interpolation is added.
- If the gap is `> 15 minutes` and `<= 60 minutes`, interpolated points are inserted every `15 minutes`.
- If the gap is `> 60 minutes`, nothing is inserted and the gap is logged.

Each interpolated point uses simple linear interpolation between the two real endpoint observations.

Example:

- `03:55 = 60.8F`
- `04:40 = 59.0F`

This produces interpolated points near `04:10` and `04:25`, each with linearly interpolated temperatures.

Interpolated station-local points are marked with `sourceKind = "interpolated"`.
Real observations are marked with `sourceKind = "observed"`.

## Forecast-Guided Extrapolation

For `NWS` only, the station-local series can also extend forward after a station’s last observation.

This is used for dropout handling when there is no later station observation available for interpolation.

Rules:

- Extrapolation is only forward from the station’s last observation.
- It runs in `15-minute` steps.
- It is capped at `1 hour`.
- It uses the forecast trend, not the forecast absolute temperature.

The model is:

- `synthetic_station_temp(t) = last_station_temp + (forecast(t) - forecast(last_obs_time))`

That preserves the station’s local offset while following the forecast’s expected temperature change.

Extrapolated station-local points are marked with `sourceKind = "forecast_extrapolated"`.

## Timestamp Resolution

After the station-local series are built, the code takes the union of all timestamps from all stations. Those timestamps become the candidate emission times for the actual-history series.

For each candidate time, `resolveStationPointForTimestamp(...)`:

- finds the nearest point in a given station’s local series
- accepts it only if it is within `15 minutes` of the target time

This means a station does not need a raw observation at the exact target minute. It only needs a station-local point close enough to participate, and that point may be real, interpolated, or forecast-extrapolated.

## Blending Step

Once the contributing station-local points are selected for a target timestamp:

- if there is one contributing station, that temperature is emitted directly
- if there are multiple stations, the code converts the station-local points into temporary observation objects and calls `SpatialInterpolator.interpolateIDW(...)`

The emitted row uses the nearest contributing station as the anchor for station metadata, but the emitted temperature is the blended value.

## Why This Reduces Jaggedness

Before this change, the blend cohort was built only from whatever raw observations existed at each timestamp.

That caused a failure mode like this:

- one point uses `AW020 + KNUQ`
- the next point uses only `AW020`
- the next point uses `AW020 + KNUQ` again

If `KNUQ` is warmer than `AW020`, the history line jumps up when `KNUQ` is present and down when it is missing.

After this change, `KNUQ` can still contribute at nearby timestamps through station-local interpolated points, and dropouts such as isolated `LOAC1` participation can be held forward briefly with forecast-guided extrapolation. The cohort is therefore more stable across adjacent windows.

This reduces station-dropout jaggedness without smoothing the final line itself.

## Important Limits

This implementation is intentionally limited:

- It does not smooth the final rendered curve.
- It only fills moderate station-local gaps.
- It inserts synthetic points at a fixed `15-minute` cadence, not at arbitrary density.
- It does not create a dense per-minute station model.
- Gaps larger than `60 minutes` are left unfilled.
- Forecast-guided extrapolation is limited to `NWS`.
- Forecast-guided extrapolation is capped at `1 hour` after the last observation.

So this is a continuity improvement, not a full resampling system.

## Logging

The implementation includes explicit diagnostics to make future debugging easier:

- `window source=... stations=... breakdown=[...]`
- `station_interpolate station=... at=... temp=... from=.....`
- `station_extrapolate station=... at=... temp=... fromObs=... forecastDelta=...`
- `station_gap station=... gapMin=... from=.....`
- `emit t=... single_station=...`
- `emit t=... blended=... stations=[...] cohortChanged=...`

These logs make it possible to determine:

- which stations were available in the visible window
- where interpolation was inserted
- where forecast-guided extrapolation was inserted
- which gaps were too large to bridge
- which station cohort produced each emitted graph point
- when the cohort changed

## Tests

Relevant tests are in:

- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerActualsTest.kt`

Important coverage:

- `mixed NWS stations IDW-blend nearby observations`
- `blend diagnostics log both single-station and cohort-change emissions`
- `station-local interpolation keeps intermittent station in later blend windows`
- `station-local interpolation fills multi-step gaps up to one hour`
- `forecast-guided extrapolation keeps last station briefly after dropout`

These verify that:

- far stations influence blends without dominating them
- interpolation/debug logs are emitted
- an intermittent station still affects later blend windows instead of dropping out immediately
- a dropped NWS station can be held forward briefly using forecast trend
