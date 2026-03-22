# Align Hourly Fetch Dot With Last Graphed Actual

## Summary
- Fix the hourly temperature widget so the dot marks the end of the solid actual-history line.
- Stop using the `current_temp.observedAt` timestamp for the hourly temperature graph marker.
- Derive the dot anchor from the final graphed actual series built for the widget render.

## Implementation
- In `TemperatureViewHandler`, compute a `lastGraphedActualAt` timestamp from the final `graphHours` list using the last item with `isActual=true`.
- Pass that timestamp into `TemperatureGraphRenderer` instead of `observedAt`.
- Update `TemperatureGraphRenderer` and `FetchDotDebug` naming so the anchor timestamp clearly represents the last graphed actual point.
- Keep header current-temperature resolution and delta decay logic unchanged.

## Tests
- Update renderer fetch-dot tests to use the new anchor timestamp name.
- Update handler fetch-dot callback tests to assert the callback tracks the last graphed actual timestamp.
- Keep existing continuity and junction tests passing with the new marker semantics.

## Assumptions
- The dot should represent the latest actual point visible in the graph, even if that point was blended or extrapolated from station observations.
- The hourly graph marker semantics are independent from the `current_temp` table used for header current temperature.
