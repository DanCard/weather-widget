# Temperature Delta Calculation

This note describes how the widget's temperature delta badge is calculated, stored, decayed, and displayed.

## Summary

The temperature delta is not an independent weather reading.

It is the difference between:

- the widget's interpolated current temperature estimate from hourly forecast data, and
- the most recent observed current temperature for the active source.

Formula:

```text
delta = observedCurrentTemp - estimatedTemp
```

If the delta is positive, the observed current temperature is warmer than the interpolated estimate.
If the delta is negative, the observed current temperature is cooler than the interpolated estimate.

Example:

```text
estimatedTemp = 68.5
observedCurrentTemp = 70.0
delta = 70.0 - 68.5 = +1.5
```

In that case the widget shows:

- current temp: `70.0°`
- delta badge: `+1.5`

## Code Path

Main resolver:

- [CurrentTemperatureResolver.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt)

Hourly estimate source:

- [TemperatureInterpolator.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/util/TemperatureInterpolator.kt)

Delta persistence:

- [CurrentTemperatureDeltaState.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/CurrentTemperatureDeltaState.kt)
- [WidgetStateManager.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt)

Badge rendering:

- [TemperatureViewHandler.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt)

## Detailed Algorithm

`CurrentTemperatureResolver.resolve(...)` performs these steps:

1. Compute `estimatedTemp` from hourly forecast rows using `TemperatureInterpolator.getInterpolatedTemperature(...)`.
2. Read any previously stored delta state for the widget.
3. Check whether that stored delta belongs to the same:
   - display source
   - latitude
   - longitude
4. If the stored delta matches the current scope, decay it linearly based on age.
5. If both `observedCurrentTemp` and `estimatedTemp` exist:
   - compare the current observed timestamp to the stored observed timestamp
   - if there is no stored delta, or the observation is newer, compute a fresh delta
6. Compute `displayTemp`:
   - if `estimatedTemp != null`, use `estimatedTemp + appliedDelta`
   - otherwise fall back to `observedCurrentTemp`
7. Return:
   - `displayTemp`
   - `estimatedTemp`
   - `observedTemp`
   - `appliedDelta`
   - optional updated delta state to persist
   - whether old stored delta should be cleared

Pseudo code:

```text
estimatedTemp = interpolate(hourlyForecasts, now, displaySource)

scopedStoredDelta =
    storedDeltaState if source/lat/lon match
    else null

appliedDelta =
    if scopedStoredDelta exists:
        decay(scopedStoredDelta.delta, scopedStoredDelta.updatedAtMs, now)
    else:
        null

if observedCurrentTemp exists and observedAt exists and estimatedTemp exists:
    hasNewObservedReading =
        scopedStoredDelta is null OR
        scopedStoredDelta.lastObservedAt != observedAt

    if hasNewObservedReading:
        appliedDelta = observedCurrentTemp - estimatedTemp
        updatedDeltaState = {
            delta = appliedDelta,
            lastObservedTemp = observedCurrentTemp,
            lastObservedAt = observedAt,
            updatedAtMs = min(observedAt, nowMs),
            sourceId = displaySource.id,
            locationLat = currentLat,
            locationLon = currentLon
        }

displayTemp =
    if estimatedTemp exists:
        estimatedTemp + (appliedDelta ?: 0)
    else:
        observedCurrentTemp
```

## Where `estimatedTemp` Comes From

`estimatedTemp` comes from hourly forecast interpolation, not from the observation feed.

The interpolator:

1. chooses hourly rows for the active source, with Generic Gap fallback
2. snaps `targetTime` down to the current hour
3. finds the current-hour and next-hour buckets
4. linearly interpolates based on `targetTime.minute / 60`

Example:

```text
10:00 forecast = 68
11:00 forecast = 71
now = 10:30

estimatedTemp = 68 + (71 - 68) * 0.5 = 69.5
```

If the observed current temperature is `71.0`, then:

```text
delta = 71.0 - 69.5 = +1.5
displayTemp = 69.5 + 1.5 = 71.0
```

## Stored Delta State

When a fresh delta is computed, the widget stores:

- raw delta
- last observed temperature
- observed fetched timestamp
- delta update timestamp
- source id
- latitude
- longitude

The stored structure is:

- [CurrentTemperatureDeltaState.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/CurrentTemperatureDeltaState.kt)

The state is persisted in widget preferences through:

- `WidgetStateManager.getCurrentTempDeltaState(...)`
- `WidgetStateManager.setCurrentTempDeltaState(...)`
- `WidgetStateManager.clearCurrentTempDeltaState(...)`

## Decay Behavior

Stored delta does not remain constant forever.

It decays linearly to zero over 4 hours.

Constant:

```text
DELTA_DECAY_WINDOW_MS = 4 hours
```

Formula:

```text
remainingFraction = 1 - elapsed / 4h
decayedDelta = rawDelta * remainingFraction
```

Examples:

- raw delta `+3.0`, elapsed `0h` -> `+3.0`
- raw delta `+3.0`, elapsed `2h` -> `+1.5`
- raw delta `+3.0`, elapsed `4h` -> `0.0`

This means a badge showing `+1.5` may be either:

- a freshly computed live delta, or
- a decayed carry-forward from an older larger delta

## Scope Matching

Stored delta is reused only when all of these match:

- `sourceId == displaySource.id`
- `locationLat == currentLat` within a tiny tolerance
- `locationLon == currentLon` within a tiny tolerance

If scope does not match, the old delta is ignored and should be cleared.

This prevents carrying a delta from:

- one API source into another
- one widget location into another

## When the Badge Is Shown

The badge is rendered in:

- [TemperatureViewHandler.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt)

It is shown only when:

1. the graph's "now" line is visible
2. `appliedDelta != null`
3. `abs(appliedDelta) >= 0.1`

Formatting:

```text
String.format("%+.1f", delta)
```

So:

- `+1.54` becomes `+1.5`
- `-0.86` becomes `-0.9`
- `+0.05` is hidden because it is below the `0.1` threshold

Colors:

- positive delta: orange
- negative delta: blue

## Important Clarification

The delta badge is tied to the resolver's estimate at `now`.

It is not the difference between:

- the observation and
- an arbitrary future/past point at the graph center time

The graph may be centered elsewhere for navigation, but the delta logic is still built from the current-time interpolation path.

## Practical Meaning of `+1.5`

When the widget shows `+1.5`, it means:

```text
observed current temperature = interpolated estimate + 1.5
```

That can mean either:

- the observation currently reads 1.5 degrees warmer than the hourly forecast interpolation, or
- an older observed-vs-estimated gap is being carried forward and has decayed to 1.5

## Logging

Relevant resolver and interpolator debug traces now go to both:

- logcat
- `app_logs` in the database

Tags:

- `CurrentTempResolver`
- `TemperatureInterpolator`

Those DB logs can be used to determine whether a displayed delta came from:

- a fresh observed reading
- a reused stored delta
- a decayed stored delta
