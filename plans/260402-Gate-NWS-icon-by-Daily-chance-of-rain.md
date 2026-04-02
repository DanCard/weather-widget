# NWS Chance-Light-Rain Mixed Icon

## Summary
- Add a new day icon that reads as partly cloudy with a small rain hint.
- Use it only for NWS daily conditions matching low-confidence light-rain phrases such as `Chance Light Rain` and `Slight Chance Light Rain`.
- Require stored daily precipitation probability to be below `35%` before using the new icon.
- Treat the new icon as mixed/cloud behavior, not a rainy icon, for tap routing and graph navigation.

## Implementation
- Add a new vector drawable based on `ic_weather_partly_cloudy` with a subtle blue raindrop accent.
- Update `DailyForecastIconResolver` so NWS daily tokens matching the verified low-confidence phrases return the new icon only when `ForecastEntity.precipProbability < 35`.
- Keep stronger precipitation phrases on the existing rainy/storm/snow icons.
- Update central icon-category helpers so the new icon is:
  - not `isRainy`
  - `isMixed`
  - cloud-eligible for routing
- Replace duplicated hourly-handler icon-category comparisons with the shared `WeatherIconMapper` helpers so the new icon behaves consistently outside daily view too.

## Tests
- Add resolver coverage for:
  - NWS `Chance Light Rain` with daily PoP below `35` -> new icon
  - NWS `Slight Chance Light Rain` with daily PoP below `35` -> new icon
  - NWS `Chance Light Rain` with daily PoP `35` or above -> standard rainy icon path
  - stronger NWS `Rain` condition still -> `ic_weather_rain`
- Add icon-category coverage confirming the new icon is mixed/cloud and not rainy.
- Add tap-routing coverage confirming the new icon routes like a mixed/cloud icon rather than precipitation.

## Assumptions
- Scope stays NWS-only for this pass.
- No night variant is added.
- The gating value is the stored daily `ForecastEntity.precipProbability`, not a recomputed daylight-only hourly value.
- Daily rain labels and RainAnalyzer thresholds remain unchanged.
