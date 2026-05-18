# Temperature Interpolation with Stale Station Reports

## Summary

The app has two separate time limits that are easy to conflate:

1. The current-temperature resolution window controls which observation rows are loaded from storage.
2. The per-station extrapolation limit controls whether one station can contribute to a later blended timestamp.

Today, current-temperature resolution uses a now-centered window of about 12 hours back and 2 hours forward. That means an observation from several hours ago can still be loaded and considered. Separately, `ObservationBlender` only lets an individual station be carried forward into a later blend point for up to 3 hours.

## Observation Blending

`ObservationBlender.blendObservationSeries()` is driven by real station observation timestamps. It does not create synthetic candidate timestamps just because a station has gone quiet.

For each candidate timestamp, each station is resolved as follows:

1. If the station has an exact observation at that timestamp, use it as `observed`.
2. If the timestamp falls between two observations from that station and the gap is 3 hours or less, linearly interpolate between those observations as `interpolated`.
3. If the timestamp is after the station's last observation and the gap is 3 hours or less, forward-extrapolate from the last observation using the hourly forecast trend as `forecast_extrapolated`.
4. If the gap is more than 3 hours, that station contributes nothing at that candidate timestamp.

When multiple stations contribute to a candidate timestamp, their resolved values are blended with inverse-distance weighting.

## Current Temperature Anchor

`ObservationBlender.resolveCurrentObservation()` does not select `forecast_extrapolated` points as the current observation anchor. It picks the latest past `observed` point first, and only falls back to the latest past `interpolated` point.

That anchor is then passed into `CurrentTemperatureResolver`. If hourly forecast data exists both for the anchor time and for now, the resolver computes:

```text
delta = observedTempAtAnchor - forecastTempAtAnchor
displayTemp = forecastTempNow + delta
```

This means the displayed current temperature can continue to follow the forecast curve while preserving the bias implied by the latest real observation anchor.

## Hourly Forecast Interpolation

`TemperatureInterpolator` handles hourly forecast rows, not station observations. It looks up the current hour and next hour, then linearly interpolates using the minute within the hour.

Example:

```text
10:00 forecast = 70.0
11:00 forecast = 72.0
10:30 display estimate = 71.0
```

If one neighboring hourly row is missing, it returns the available row. If both neighboring rows are missing, it falls back to the closest hourly row.

## Clarifying Example

Suppose:

```text
Station A reports at 10:00
Station B reports at 12:30
Now is 14:00
```

When blending the point at 12:30, Station A can still contribute because its report is only 2.5 hours old. Its contribution is:

```text
Station A observed temp at 10:00
+ forecast change from 10:00 to 12:30
```

If Station A last reported at 08:00 instead, then at the 12:30 blend point it is 4.5 hours old. That exceeds the 3-hour extrapolation limit, so Station A is ignored for that blend point.

The confusing case is an observation from 4 hours ago. It may still be loaded because the current-temp query window looks back about 12 hours. But being loaded does not mean it can be extrapolated into every later blend point. The rules are:

```text
12-hour query window = which rows are loaded from the database
3-hour extrapolation limit = whether a station can contribute to a later blended timestamp
```

One more nuance: a 4-hour-old real observation can still be selected as the current-temperature anchor if it is the latest actual or interpolated point available. After that, `CurrentTemperatureResolver` applies the anchor's forecast delta to the current hourly forecast estimate.

## Relevant Code

- `app/src/main/java/com/weatherwidget/util/ObservationBlender.kt`
- `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt`
- `app/src/main/java/com/weatherwidget/util/TemperatureInterpolator.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/GraphDataLoader.kt`
