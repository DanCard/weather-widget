# Daily Taps Use Hourly Icon-Home Routing

## Summary
- Make daily-view day taps use the same icon-to-home-graph routing already used by hourly bottom-row icon taps.
- Route by rendered icon, not by rain summary or daily precip threshold.

## Changes
- Reuse the existing icon-home mapping in `DayClickHelper`:
  - rainy icon -> `PRECIPITATION`
  - cloud-eligible icon -> `CLOUD_COVER`
  - otherwise -> `TEMPERATURE`
- Update `DailyViewHandler.buildDayClickIntent(...)` to accept the displayed `iconRes` and derive the target mode from that icon unless an override is supplied.
- Apply the same rule to:
  - daily text-mode day taps
  - daily graph top-zone taps
  - daily graph bottom-zone taps
- Keep past-day taps opening `ForecastHistoryActivity`.
- Keep the existing hourly offset calculation for centering future-day graph opens.

## Tests
- Update `DayClickHelperTest` to assert icon-based routing.
- Update `DailyViewHandlerTest` and `DailyViewHandlerIntentContractTest` to verify the day-click intent target view comes from the icon.

## Defaults
- Reuse `WeatherIconMapper.isCloudForecastEligible(...)` as the cloud-graph bucket, matching the existing hourly icon-home behavior.
