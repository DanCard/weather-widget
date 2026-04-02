# NWS Chance-Light-Rain Mixed Icon

## Summary
- Add a new day icon that reads as partly cloudy with a small rain hint.
- Use it only for NWS daily conditions matching low-confidence light-rain phrases such as `Chance Light Rain` and `Slight Chance Light Rain`.
- Treat the new icon as mixed/cloud behavior, not a rainy icon, for tap routing and graph navigation.

## Implementation
- Add a new vector drawable based on `ic_weather_partly_cloudy` with a subtle blue raindrop accent.
- Update `DailyForecastIconResolver` so NWS daily tokens matching the verified low-confidence phrases return the new icon before generic condition mapping runs.
- Keep stronger precipitation phrases on the existing rainy/storm/snow icons.
- Update central icon-category helpers so the new icon is:
  - not `isRainy`
  - `isMixed`
  - cloud-eligible for routing
- Replace duplicated hourly-handler icon-category comparisons with the shared `WeatherIconMapper` helpers so the new icon behaves consistently outside daily view too.

## Tests
- Add resolver coverage for:
  - NWS `Chance Light Rain` -> new icon
  - NWS `Slight Chance Light Rain` -> new icon
  - stronger NWS `Rain` condition still -> `ic_weather_rain`
- Add icon-category coverage confirming the new icon is mixed/cloud and not rainy.
- Add tap-routing coverage confirming the new icon routes like a mixed/cloud icon rather than precipitation.

## Assumptions
- Scope stays NWS-only for this pass.
- No night variant is added.
- Daily rain labels and RainAnalyzer thresholds remain unchanged.
