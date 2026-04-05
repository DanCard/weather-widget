# End Actual Line at Last Observation Dot

## Summary

The temperature graph currently builds one smooth actual path across all available `actualTemperature` points, including carry-forward continuity buckets, then clips that path at the anchor X. That stops the visible line roughly at the right horizontal position, but it does not guarantee the path itself ends on the last observation dot.

The fix is to build the visible solid actual line from only the points up to the real observation anchor, plus an explicit terminal point at the fetch dot / anchor position. That keeps carry-forward data for continuity logic without letting later synthetic points distort the path endpoint.

## Implementation

1. In `TemperatureGraphRenderer`, compute a dedicated anchored actual-line path:
   - keep `transitionX` as the visible stop position
   - if `fetchDotX` and `lastObservedTemp` are available, use them as the terminal X/Y
   - otherwise terminate at the last visible actual point
   - build a truncated point list ending exactly at that terminal point

2. Draw the solid actual line from the anchored path instead of the full `originalPath`.
   - preserve existing fill, dashed forecast, ghost line, and fetch-dot behavior
   - keep the left-side `clipRect` so the current clip-based regression tests still hold

3. Add a small renderer debug hook for the solid actual line endpoint so tests can assert the line’s true rendered endpoint rather than infer it indirectly from clip bounds.

## Tests

- Extend `TemperatureGraphJunctionTest` to assert the actual line endpoint Y matches the fetch dot Y when the observation falls between graphed hours.
- Extend `TemperatureGraphRendererActualsTest` to assert the anchored actual endpoint is at the last real observation, not a later carry-forward actual bucket.
- Run targeted graph tests plus `./gradlew testDebugUnitTest`.

## Assumptions

- Upstream anchor selection is already correct: `observedAt` represents the last real observed anchor.
- The bug is renderer geometry, not current-temperature selection.
- Carry-forward `isActual` buckets should remain in `HourData`, but must not affect the visible terminal point of the solid actual line.
