# Google Maps Geocoding API with Nominatim fallback

## Motivation

OpenStreetMap Nominatim is strict about unit numbers, typos, and venue names, and can return duplicate building footprints for a single street address (e.g., two identical `7900 East Princess Drive, Scottsdale, AZ 85255` entries ~100m apart).

Google Maps Geocoding API (`maps.googleapis.com/maps/api/geocode/json`) has a 10,000 free requests/month Essentials tier separate from Google Weather API, parses unit/subpremise numbers cleanly, and returns a single rooftop match (`7900 E Princess Dr, Scottsdale, AZ 85255`).

## GCP setup (completed)

- Enabled `geocoding-backend.googleapis.com` on project `fun-weather-widget`.
- Updated `Weather API key` restrictions to allow both `weather.googleapis.com` and `geocoding-backend.googleapis.com`.

## Design

1. **`GoogleGeocodingApi` (`:shared`)**:
   - Calls `https://maps.googleapis.com/maps/api/geocode/json?address=<q>&language=en&key=<apiKey>`.
   - Never logs the URL or API key; logs query, HTTP status, Google status, and result count via `Log.d`.
   - Formats US `formatted_address` by stripping trailing `", USA"` so it matches Nominatim's US postal label style (`7900 E Princess Dr, Scottsdale, AZ 85255`).
   - Returns `emptyList()` on `ZERO_RESULTS` and throws on `OVER_QUERY_LIMIT`, `REQUEST_DENIED`, `INVALID_REQUEST`, or non-2xx HTTP so `SharedLocationResolver` logs and falls back to Nominatim.
2. **`SharedLocationResolver.searchText` (`:shared`)**:
   - Accepts optional `googleGeocodingApi: GoogleGeocodingApi? = null`.
   - When configured, tries Google Geocoding first on the normalized query; on empty result or failure (preserving `CancellationException`), falls back to Nominatim with `AddressQueryCleaner.candidateQueries`.
3. **`ApiUsageClassifier` (`:shared`)**:
   - Tracks `maps.googleapis.com` under `GOOGLE_GEOCODING` (display name `"Google Geocoding"`, quota zone `GoogleQuota.ZONE`) so Usage Stats tracks it separately from `GOOGLE_WEATHER`.
4. **Platform wiring**:
   - Android (`AppModule.kt`): provides `GoogleGeocodingApi` using `BuiltInApiKeys.effectiveKey(WeatherSource.GOOGLE_WEATHER, widgetStateManager)`.
   - Desktop (`DesktopUiApplication.kt`): wires `GoogleGeocodingApi` using user `config?.apiKeys[GOOGLE_WEATHER]` falling back to `DesktopApiKeys.DEFAULTS[GOOGLE_WEATHER]`.
5. **Tests**:
   - `GoogleGeocodingApiTest` (Ktor `MockEngine`: success, US label formatting, non-US label, `ZERO_RESULTS`, `OVER_QUERY_LIMIT` throws, blank key returns empty without HTTP call).
   - `SharedLocationResolverTest` (Google primary match, Google `ZERO_RESULTS` fallback to Nominatim, Google error fallback to Nominatim).
   - `ApiUsageClassifierTest` (verify `maps.googleapis.com/maps/api/geocode/json` classifies as `GOOGLE_GEOCODING`).
6. **Verification**:
   - Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
