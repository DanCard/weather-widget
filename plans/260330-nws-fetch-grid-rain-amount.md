# NWS Rain Amount via Grid Data Fallback

## Summary
Add NWS rain-amount support from `forecastGridData` and use it as the preferred source for `precipAmountMm`. Keep the existing `/forecast` and `/forecast/hourly` parsing as fallback inputs, but stop depending on them for amount when grid data is available.

## Key Changes
- Extend the NWS client to fetch `gridpoints/{gridId}/{x},{y}` quantitative precipitation data in addition to the existing forecast and hourly endpoints.
- Parse `properties.quantitativePrecipitation.values` into time-ranged mm values, preserving the NWS units conversion logic already used for per-period `quantitativePrecipitation`.
- In `ForecastRepository.fetchFromNws`:
  - fetch grid QPF alongside daily forecast, hourly forecast, and sky cover
  - derive hourly `precipAmountMm` by mapping each hourly forecast period to the overlapping grid-data interval
  - derive daily `precipAmountMm` by summing the hourly amounts for that local day
  - if grid-data QPF is unavailable or missing for a given hour/day, fall back to the current per-period `quantitativePrecipitation` values from `/forecast/hourly` or `/forecast`
- Keep precip probability sourcing unchanged: continue using `/forecast` and `/forecast/hourly` for `precipProbability`.
- Keep UI behavior unchanged after data ingestion: the existing daily-view amount-vs-percent rule should consume the richer `precipAmountMm` values automatically.

## Implementation Details
- Add a new NWS model for grid QPF intervals:
  - start time
  - end time
  - amount in mm
- Add a new `NwsApi` method to fetch and parse grid QPF intervals from `forecastGridData`.
- When merging NWS data:
  - prefer grid-data hourly amount if any overlapping interval provides a value
  - sum multiple overlapping grid intervals if needed
  - treat explicit zero from grid data as a real value, not as missing
  - only fall back to period-level amount when grid data has no value for that hour/day
- Use the same local timezone handling already used for NWS hourly grouping so day totals align with widget dates.
- Leave non-NWS providers unchanged.

## Test Plan
- `NwsApi` parser tests:
  - grid-data QPF parses `wmoUnit:mm`
  - grid-data QPF converts `wmoUnit:in` to mm
  - missing QPF values yield no intervals
  - explicit zero QPF is preserved
- `ForecastRepository` integration tests:
  - hourly NWS rows store non-null `precipAmountMm` from grid data
  - daily NWS forecast stores the sum of hourly grid-data amounts
  - grid-data amount overrides period-level amount when both exist
  - period-level amount is used when grid data is absent
  - zero grid-data amount does not trigger fallback to non-zero period amount
- Acceptance check against live NWS:
  - for a point like `MTR/93,87`, confirm `forecastGridData` has QPF even when `/forecast` and `/forecast/hourly` expose `quantitativePrecipitation = null`

## Assumptions
- Default choice: use `forecastGridData` as the authoritative NWS rain-amount source, with current forecast endpoints as fallback only.
- Extra NWS request cost is acceptable for NWS fetches.
- Aggregation should follow local-day widget semantics, not raw UTC interval boundaries.
