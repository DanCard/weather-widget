# Cloud-Cover-Driven Slight-Chance Precip Icons

## Summary
- Replace the current `isSlightChance -> partly cloudy` fallback in `WeatherIconMapper`.
- When the condition text indicates slight/patchy precipitation, choose the icon from `cloudCover` instead of always returning `partly cloudy`.
- Keep definite rain/snow/storm phrases on their existing precipitation icons.

## Implementation
- Update `WeatherIconMapper.getIconResource(...)` to route slight/patchy precipitation phrases through a dedicated cloud-cover helper.
- Apply the helper to slight/patchy rain, drizzle, showers, snow, flurries, blizzard, thunder, and storm phrases.
- Use these thresholds:
  - `0..25` -> `mostly_clear` / `night`
  - `26..74` -> `partly_cloudy` / `partly_cloudy_night`
  - `75..90` -> `mostly_cloudy` / `mostly_cloudy_night`
  - `91..100` -> `cloudy`
- If `cloudCover` is null, preserve the current slight/patchy fallback to `partly_cloudy` / `partly_cloudy_night`.

## Tests
- Update `WeatherIconMapperTest` coverage for slight/patchy precip with cloud cover at each tier.
- Add at least one non-rain slight/patchy precip test to confirm the same helper is used for storms or snow.
- Add a regression test confirming null `cloudCover` still falls back to partly cloudy.

## Assumptions
- Only slight/patchy precipitation phrases use cloud-cover-driven icon selection.
- Existing behavior for non-precipitation phrases remains unchanged.
- `cloudy` has no separate night variant in the current drawable set, so `91..100` maps to `ic_weather_cloudy` for both day and night.
