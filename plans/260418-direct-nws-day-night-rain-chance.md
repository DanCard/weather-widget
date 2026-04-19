# Direct NWS Day/Night Rain Chance

## Summary

Store separate NWS daytime and nighttime precipitation probabilities from the NWS 12-hour forecast periods. Use direct NWS period values for future daily rain labels, while keeping hourly precipitation available for today's current rain chance behavior.

## Implementation

1. Add nullable `daytimePrecipProbability` and `nighttimePrecipProbability` columns to `ForecastEntity`.
2. Bump Room from version 43 to 44, add `MIGRATION_43_44`, register it, and export the updated schema.
3. Update `NwsForecastMapper` so:
   - future daytime rain chance comes from the NWS `isDaytime=true` forecast period,
   - future nighttime rain chance comes from the NWS `isDaytime=false` forecast period keyed by its `startTime` date,
   - hourly max precipitation only seeds today's daily/current rain chance.
4. Update daily graph logic so top future rain labels use the direct NWS daytime chance and bottom night labels use direct NWS nighttime chance.
5. Render bottom night labels as percent-only text under the low temperature stack, only when the top/day label is absent, the threshold is met, and placement does not collide or overflow.

## Thresholds

1. Tonight: show only when direct NWS nighttime chance is greater than 50%.
2. Tomorrow night: show only at 55% or higher.
3. Each following night: increase the threshold by 5 percentage points per day, capped by 100%.

## Tests

1. Mapper tests for direct period storage and future-day hourly max suppression.
2. Daily logic tests for threshold behavior and suppression when a day label exists.
3. Renderer tests for bottom placement and collision/overflow skipping.

## Assumptions

1. Existing non-NWS providers keep their current behavior.
2. The existing top label can remain visually unchanged, but its NWS future-day input must be the direct NWS daytime period chance.
3. The bottom night label is graph-mode only; text-mode widgets are unchanged.
