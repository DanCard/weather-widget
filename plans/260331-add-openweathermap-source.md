# Add OpenWeatherMap as Default Second-Priority Source

## Summary
Add `OpenWeatherMap` as a fifth weather source and make it the second visible source for new installs. Existing installs keep their current source order unless the user changes it. `OpenWeatherMap` requires an API key and should behave as a normal configured provider.

## Key Changes
- Add a new `WeatherSource.OPEN_WEATHER_MAP` entry with stable `id`, display name, and short label, and include it everywhere source enums are mapped from stored and display values.
- Add a new remote client for OpenWeatherMap forecast and current fetching, normalized to the app’s existing daily, hourly, and current-reading shapes used by `ForecastRepository` and `CurrentTempRepository`.
- Wire the new client through Hilt in `AppModule`, including API-usage host logging for the OpenWeatherMap domain.
- Extend `ForecastRepository` so OpenWeatherMap participates in staleness checks, parallel source fetches, forecast snapshot persistence, hourly forecast persistence, and source-specific failure logging.
- Extend `CurrentTempRepository` so OpenWeatherMap participates in ordered current-temp refresh, observation persistence, and source-specific logging and station naming.
- Update settings and UI source lists so OpenWeatherMap appears as a configurable source with description text and can be reordered like the others.
- Change the new-install default visible-source order in `WidgetStateManager` to `NWS, OPEN_WEATHER_MAP, OPEN_METEO, SILURIAN`. `WEATHER_API` remains available in settings but is not enabled by default for new installs.
- Preserve existing installs exactly as-is; do not inject OpenWeatherMap into already stored source orders.
- Add required build and config plumbing for `OPEN_WEATHER_MAP_API_KEY`, with clear failure behavior if the source is enabled but the key is missing.

## Public / Config Surface
- New source identifier: `OPEN_WEATHER_MAP`
- New required config value: `OPEN_WEATHER_MAP_API_KEY`
- Updated default visible sources for fresh installs only
- Updated settings copy and source descriptions

## Test Plan
- Unit tests for OpenWeatherMap response parsing:
  - daily forecast normalization
  - hourly forecast normalization
  - current reading normalization
  - missing and partial-field handling
- Repository and state tests covering:
  - source selection and fetch participation
  - persistence under `OPEN_WEATHER_MAP`
  - missing-key failure behavior
  - fresh-install default order includes OpenWeatherMap second
  - existing stored orders are preserved unchanged
- Manual verification:
  - new install shows toggle order `NWS -> OpenWeatherMap -> Open-Meteo -> Silurian`
  - WeatherAPI is available in settings but initially hidden
  - current temp and forecast render correctly when OpenWeatherMap is selected
  - missing or invalid key yields logged failure without breaking other enabled sources

## Assumptions
- OpenWeatherMap is being added, not replacing `WeatherAPI`.
- Existing installs must not have their source order changed automatically.
- OpenWeatherMap supports both forecast and current-temperature paths so it behaves like the other full providers.
- API-key setup is required when this source is intended to run.
