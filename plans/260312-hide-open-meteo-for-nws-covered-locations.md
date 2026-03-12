# Hide Open-Meteo As a Live Source When NWS Covers the Location

## Summary
- Treat Open-Meteo as a non-user-facing live source for any widget location where NWS gridpoint lookup succeeds.
- Keep Open-Meteo for non-U.S./non-NWS-covered locations.
- Stop Open-Meteo live forecast and current-temp fetches for NWS-covered locations.
- Keep Open-Meteo climate-normal usage unchanged.

## Key Changes
- Add a small availability policy layer that answers `isNwsAvailable(lat, lon)` using NWS gridpoint success, with short-lived caching to avoid repeated probe traffic.
- Update source-list construction so the widget toggle, Settings source list, forecast-history source cycle, and observations source cycle all use an effective visible-source list for the current location, not the raw global preference list.
- Preserve the stored global source order in preferences, but filter `OPEN_METEO` out at runtime when NWS is available for the active location.
- If a widget's current display source is `OPEN_METEO` and the location becomes NWS-covered, automatically fall back to the first remaining effective source.
- Update forecast fetch policy in `ForecastRepository` so Open-Meteo live forecasts are not fetched for NWS-covered locations, including charging-time background fetches.
- Update current-temperature fetch policy in `CurrentTempRepository` so Open-Meteo current readings are not fetched for NWS-covered locations unless explicitly requested for a non-covered location.
- Keep `OpenMeteoApi.getClimateForecast(...)` and climate-gap filling unchanged.

## Interfaces / Behavior Changes
- Introduce a location-aware source-filtering API, likely on a dedicated helper or `WidgetStateManager`, returning effective visible sources for a lat/lon pair.
- Introduce a reusable NWS-availability check helper instead of scattering `getGridPoint` probes through UI and repositories.
- No database schema change and no preference migration required beyond runtime filtering.

## Test Plan
- Unit test: NWS-covered location filters `OPEN_METEO` from effective visible sources but keeps other enabled sources in order.
- Unit test: non-covered location leaves `OPEN_METEO` available.
- Unit test: if persisted display source is `OPEN_METEO` and location becomes NWS-covered, fallback source selection is deterministic.
- Repository test: `ForecastRepository` does not schedule/fetch Open-Meteo live forecasts for NWS-covered locations, including charging cases.
- Repository test: `CurrentTempRepository` skips Open-Meteo current fetch for NWS-covered locations.
- UI behavior check: Settings and widget source toggle never show `Meteo` for an NWS-covered location, but still show it for a non-covered location.
- Regression check: climate normals still load through Open-Meteo when forecast coverage needs gap fill.

## Assumptions
- "Where NWS is available" means NWS gridpoint lookup succeeds for that lat/lon, not merely inside U.S. borders.
- WeatherAPI and Silurian remain user-visible in NWS-covered locations unless separately removed later.
- Open-Meteo remains in the codebase for non-NWS-covered live forecasts and for climate normals everywhere.
