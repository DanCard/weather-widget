# Daily Icons Native Token Plan

## Summary

Add a nullable provider-native daily icon field to `forecasts`, migrate Room from `42` to `43`, populate the new field on every provider fetch, and make daily-mode icons prefer that stored native token before falling back to the existing `condition` mapping.

## Schema / API Changes

- Extend `ForecastEntity` with:
  - `nativeDailyIconToken: String? = null`
- Use one generic string field rather than provider-specific columns.
  - `NWS`: store the NWS daily `shortForecast` string used as the provider’s recommended day summary icon hint.
  - `OPEN_METEO`: store the raw daily `weatherCode` as a string, e.g. `"3"`.
  - `VISUAL_CROSSING`: store the raw daily `icon` token from the API, e.g. `"partly-cloudy-day"`.
  - `WEATHER_API`: store the raw daily `condition.icon` URL path or the provider’s numeric/icon token if available from the response.
  - `SILURIAN`: store the raw daily `weather_code` string already returned by the API.
  - `OPEN_WEATHER_MAP`: store the raw `weather[0].icon` token if available, otherwise the current description remains the fallback.
- Add `MIGRATION_42_43` in `WeatherDatabase.kt`:
  - bump DB version to `43`
  - `ALTER TABLE forecasts ADD COLUMN nativeDailyIconToken TEXT`
  - do not backfill old rows; leave `NULL` so legacy data uses condition fallback
- Add the new migration to `.addMigrations(...)`.

## Provider Fetch Changes

- Update remote models to preserve raw icon/token fields where the APIs expose them:
  - `VisualCrossingApi.DailyForecast`: add `iconToken`
  - `WeatherApi.DailyForecast`: add `iconToken`
  - `OpenWeatherMapApi.DailyForecast`: add `iconToken`
  - `OpenMeteoApi.DailyForecast`: keep `weatherCode` and persist it directly as the token string
  - `SilurianApi.DailyForecast`: keep `condition` but also persist the raw weather-code string as the token
  - `NWS`: use the existing daily forecast summary source string as the native token for now; no separate icon asset token exists in current storage
- Update every `ForecastEntity(...)` creation site in `ForecastRepository.kt` to populate `nativeDailyIconToken`.
- Preserve existing `condition` population unchanged so current UI and older fallback logic remain intact.

## Daily Icon Resolution

- Add a dedicated daily resolver, e.g. `DailyForecastIconResolver`.
- Resolution order:
  1. Interpret `nativeDailyIconToken` using provider-aware logic.
  2. If token is absent or unsupported, map `condition` through `WeatherIconMapper`.
  3. If both are absent, use unknown/fallback behavior.
- Provider-aware token handling:
  - `OPEN_METEO`: map raw numeric weather codes directly.
  - `VISUAL_CROSSING`: map `icon` tokens like `clear-day`, `partly-cloudy-day`, `rain`, `snow`.
  - `WEATHER_API`: derive icon category from the provider icon token/URL when available; otherwise fall back to condition text.
  - `OPEN_WEATHER_MAP`: map icon codes like `01d`, `02d`, `10d`.
  - `SILURIAN`: map known weather-code strings directly; fall back to condition text for unknown codes.
  - `NWS`: treat the stored token as the daily summary text and feed it through the existing condition mapper.
- Use this resolver in daily-mode only:
  - header icon in `DailyViewHandler.kt`
  - per-day icons in `DailyViewLogic.kt`
- Leave hourly, temperature, precip, and cloud-cover views unchanged.

## Test Plan

- Add a migration test covering `42 -> 43` that verifies:
  - the new column exists
  - existing forecast rows survive
  - existing rows have `NULL` `nativeDailyIconToken`
- Add unit tests for the new daily resolver:
  - `OPEN_METEO` weather codes
  - `VISUAL_CROSSING` icon tokens
  - `OPEN_WEATHER_MAP` icon codes
  - `WEATHER_API` icon token/URL parsing
  - `SILURIAN` weather-code strings
  - `NWS` daily summary text fallback
- Add regression tests proving daily-mode today icon uses `nativeDailyIconToken` when present and otherwise falls back to `condition`.
- Add a repository-level test or focused parser tests verifying each provider populates `nativeDailyIconToken` on daily forecast rows.

## Assumptions

- A single nullable `nativeDailyIconToken` column is sufficient and preferable to multiple provider-specific columns.
- Old rows are not backfilled during migration; only newly fetched rows gain native tokens.
- “Preserve native daily icon tokens” means persisting provider-native identifiers when exposed by the API, not storing or bundling provider image assets.

## Provider Image Assets

I would not store provider image assets for this app.

The practical problems outweigh the benefit:

- Most providers expose unstable icon URLs or token systems, not a durable asset contract. That binds widget rendering to remote asset semantics the app does not control.
- Remote/provider-branded icons create offline, caching, sizing, and dark/light contrast problems in a widget context where `RemoteViews` rendering is already constrained.
- You would need asset fetch, cache invalidation, disk management, failure fallback, and likely per-density normalization. That is a lot of complexity for a small visual gain.
- Licensing and redistribution can get messy quickly if the app persists and reuses provider artwork locally.

What is worth storing:

- A provider-native icon token/code in Room.
- A local mapping from that token/code to the app’s own drawable set.

That preserves provider intent without introducing asset-pipeline or legal risk. If higher fidelity is needed later, expanding the local drawable set and mapping rules is a better next step than fetching and storing provider images.
