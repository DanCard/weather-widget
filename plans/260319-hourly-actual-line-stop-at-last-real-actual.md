# Stop Hourly Actual Line At Last Real Observation

## Summary
- Fix the hourly temperature graph so the solid actual-history line ends at the last real observed or blended actual point.
- Keep existing carry-forward behavior for past gaps, but do not let carried filler extend the fetch-dot anchor or visual endpoint.

## Implementation
- Add an internal `isObservedActual` marker to `TemperatureGraphRenderer.HourData`.
- Set `isObservedActual=true` only for buckets backed by a real top-of-hour or sub-hour blended observation in `TemperatureViewHandler.buildHourDataList`.
- Keep carry-forward past-gap buckets as `isActual=true` when they reuse the last value, but force `isObservedActual=false` for those synthetic points.
- In `TemperatureViewHandler.updateWidget`, derive `actualSeriesAnchorAt` from the last hour with `isObservedActual=true` instead of the last hour with `isActual=true`.

## Tests
- Add a handler regression test covering a carried-forward top-of-hour bucket after the last real sub-hour observation and assert only the real observation remains the anchor.
- Add a renderer regression test proving an earlier `actualSeriesAnchorAt` stops the solid line even when later buckets still have `isActual=true`.
- Run the temperature graph and handler actuals test slice to verify no behavior regressions in fetch-dot continuity and actual blending.

## Assumptions
- The visual line may remain continuous through past gaps, but the terminal point and fetch dot should represent the last real observation, not synthetic carry-forward.
- This is an internal widget-rendering fix only; no schema, API, or settings changes are required.
