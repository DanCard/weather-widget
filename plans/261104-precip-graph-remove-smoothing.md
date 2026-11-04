# Remove smoothing from the rain-chance graph

## Evidence (Fold 4, 2026-11-04 03:28, NWS, NARROW zoom, panned 1a–5a)

1. Stored NWS hourly (fetch 03:23, 37.424/-122.088): 1a=25, 2a=25, 3a=25, 4a=0, 5a=0.
   Live `api.weather.gov` agrees: one 6 h PoP block 22:00–04:00 PST at 25%.
2. Screenshot: curve is ~19% at 3a with a "19%" label, and does not reach 0% until ~5a.
3. NARROW span ≤ 6 h → `HourlyZoomRules.smoothIterationsForSpan` = 1 pass of the
   `[¼, ½, ¼]` box filter. 3a = ¼·25 + ½·25 + ¼·0 = 18.75 → "19%". 4a = 0 + 0 + ¼·25 = 6.25.

## Root cause

`SeriesSmoothing.smoothValuesPreservingAllExtrema` only pins local extrema. The last hour of a
plateau (3a) is not an extremum, so the box filter pulls it toward its neighbour. The curve and
its value labels then show percentages the source never published. Both platforms do this:

- Android `PrecipitationGraphRenderer.calculateLayout` (also a "far-out" variant that pins
  even fewer points).
- Desktop `PrecipitationGraph.kt:121`.

No smoothing is needed for shape: both platforms draw through the points with the monotone
Catmull-Rom tangents in `:shared` `CurveMath`, which hit every hourly value exactly, flatten at
plateaus, and never overshoot. Without the box filter, 3a draws at 25% and the curve eases to
0% at 4a.

## Fix

1. `:shared`: add `PrecipGraphSeries.values(probabilities)` — clamp to 0..100, no smoothing.
   Both platforms call it, so the rule lives in one place.
2. Android: `PrecipitationGraphRenderer` uses it; drop the `smoothIterations` parameter, the
   far-out branch, and `PrecipViewHandler`'s `zoom.smoothIterations` argument.
3. Desktop: `PrecipitationGraph` uses it; stops reading `setup.smoothIterations`.
4. Leave out of scope: cloud-cover graphs and temperature (same filter, not reported).
   `HourlyZoomRules.smoothIterationsForSpan` stays for cloud cover.

## Tests

1. `:shared` unit test: `[25,25,25,0,0]` → unchanged; out-of-range values clamp.
2. Android Robolectric (`PrecipitationGraphRendererLogRoboTest` or sibling): plateau-edge case —
   point y at 3a equals the 25% height and no label reads 19. Fails on old code.
   Update the existing "37% (smoothed value)" assertion to the raw value.
3. Desktop: test that the precip graph's plotted values equal the raw probabilities for the
   plateau case.
4. `./scripts/unit-tests.sh`.

## Verification

1. `installDebug` to the Fold; screenshot the same 1a–5a NWS view: curve flat at 25% through
   3a, 0% at 4a, no "19%" label.
2. Rebuild/restart desktop; same NWS window shows the same shape.
