# Past-forecast extrema: window edges (incl. NOW) are never highs/lows

## Symptom (desktop, 2026-10-03 03:18)

Hourly graph showed a white `64°` near the bottom middle, beside/over the fetch dot, 50 px left of
the real forecast low `63°` (04:00).

```
LabelAccepted: displayed="64" t=02:00 role=PAST_FORECAST_LOW reason=EXTREMA val=64.0 idx=107
LabelAccepted: displayed="63" t=04:00 role=LOW               reason=EXTREMA val=63.0 idx=120
```

labelTemps: 80 → … → 64.08, then 64.0 ×13 (idx 107–119, ~02:00–03:00, NOW inside the run),
then 63 ×4, 65, 69, 74, 79, 86.

## Root cause

`TemperatureExtrema.compute()` took `pastForecastHigh/LowIndex` as a bare `max/minByOrNull` over
`0..actualEndIndex`. The forecast was still descending (plateaued) when NOW cut the segment, so
the "low" was just the first sample of a flat run that ends at the NOW edge — not a valley.

The actual series already has the rule (user, 2026-06-28): window edges, including NOW, are never
extrema, and an edge that is more extreme blocks any interior substitute. The past-forecast
extrema never got it.

## Fix

`:shared` only (Android and desktop both consume `TemperatureExtrema`):

- Keep the segment's ABSOLUTE max/min (no filter-then-max substitution).
- Accept it only if it is a turning point *inside the segment*, plateau-aware: walking left across
  equal values must reach a strictly less-extreme sample at index ≥ segment start, and walking right
  must reach one at index ≤ `actualEndIndex`. A plateau that runs into either edge (or into NaN)
  → index `-1` (no label).

## Out of scope

The fetch dot itself is not a label-engine hard bound (only its value/age text is). With the bogus
label gone this frame no longer collides; making the dot a hard bound changes engine-wide flip
behaviour and is a separate decision.

## Tests

`TemperatureExtremaPastForecastEdgeTest` (`:shared`):
- descending into a NOW-straddling plateau → `pastForecastLowIndex == -1` (this report's shape)
- monotonic descent to NOW → `-1`
- genuine interior valley (with plateau) before NOW → labeled
- warm left edge → `pastForecastHighIndex == -1`; interior peak → labeled

Then full `:shared` + `:app` unit tests, rebuild/restart desktop, screenshot.
