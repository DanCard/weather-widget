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
4. Gather all timestamps from all station-local series, including interpolated ones.
5. For each candidate timestamp, ask every station for its nearest local point within the allowed window.
6. If only one station contributes, emit that station’s temperature directly.
7. If multiple stations contribute, run inverse-distance weighting (IDW) across those station-local points.
8. Emit a blended observation for that timestamp and log the full cohort details.

## Station-Local Interpolation

`buildStationTimeSeries(...)` groups observations by `stationId` and sorts each station’s observations by timestamp.

For each pair of consecutive observations in a station:

- If the gap is `<= 15 minutes`, no interpolation is added.
- If the gap is `> 15 minutes` and `<= 30 minutes`, exactly one midpoint sample is inserted.
- If the gap is `> 30 minutes`, nothing is inserted and the gap is logged.

The interpolated midpoint uses simple linear interpolation between the two real observations.

Example:

- `03:55 = 60.8F`
- `04:15 = 59.0F`

This produces a midpoint near `04:05` with a linearly interpolated temperature.

Interpolated station-local points are marked with `sourceKind = "interpolated"`.
Real observations are marked with `sourceKind = "observed"`.

## Timestamp Resolution

After the station-local series are built, the code takes the union of all timestamps from all stations. Those timestamps become the candidate emission times for the actual-history series.

For each candidate time, `resolveStationPointForTimestamp(...)`:

- finds the nearest point in a given station’s local series
- accepts it only if it is within `15 minutes` of the target time

This means a station does not need a raw observation at the exact target minute. It only needs a station-local point close enough to participate, and that point may be real or interpolated.

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

After this change, `KNUQ` can still contribute at nearby timestamps through its station-local interpolated midpoint, so the cohort is more stable across adjacent windows.

This reduces station-dropout jaggedness without smoothing the final line itself.

## Important Limits

This implementation is intentionally limited:

- It does not smooth the final rendered curve.
- It only fills moderate station-local gaps.
- It inserts at most one midpoint for a gap.
- It does not create a dense per-minute station model.
- Gaps larger than `30 minutes` are left unfilled.

So this is a continuity improvement, not a full resampling system.

## Logging

The implementation includes explicit diagnostics to make future debugging easier:

- `window source=... stations=... breakdown=[...]`
- `station_interpolate station=... at=... temp=... from=.....`
- `station_gap station=... gapMin=... from=.....`
- `emit t=... single_station=...`
- `emit t=... blended=... stations=[...] cohortChanged=...`

These logs make it possible to determine:

- which stations were available in the visible window
- where interpolation was inserted
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

These verify that:

- far stations influence blends without dominating them
- interpolation/debug logs are emitted
- an intermittent station still affects later blend windows instead of dropping out immediately
