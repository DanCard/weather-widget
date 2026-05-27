# Make Silurian Rain Amounts Match Other Sources

## Summary
Silurian's current parser does not read the live API field for rainfall amount. The API schema exposes `precipitation_accumulation` on both daily and hourly forecasts, and this app requests Silurian with `units=imperial`, so that value is in inches. The shared app model expects `precipAmountMm`, so Silurian needs to parse `precipitation_accumulation` and convert it to millimeters before storage/rendering.

## Key Changes
1. Update `SilurianApi.parsePrecipAmountMm(...)` to read `precipitation_accumulation`.
2. Convert `precipitation_accumulation` from inches to millimeters for Silurian's current `units=imperial` requests.
3. Keep existing fallback support for `precipitation_mm` and `precipitation_amount_mm` as already-millimeter legacy/test fields.
4. Do not change graph or daily-view rendering logic; those already show rain amounts whenever `precipAmountMm` is populated.

## Tests
1. Extend `SilurianApiTest` daily mock with `precipitation_accumulation`, then assert `result.daily[0].precipAmountMm`.
2. Extend `SilurianApiTest` hourly mock with `precipitation_accumulation`, then assert `result.hourly[0].precipAmountMm`.
3. Use inch-to-millimeter assertions, proving Silurian matches the internal unit contract used by NWS, Open-Meteo, and other sources.
4. Run focused test: `./gradlew test --tests com.weatherwidget.data.remote.SilurianApiTest`.

## Assumptions
1. Keep Silurian requests as `units=imperial` so existing Fahrenheit temperature behavior remains unchanged.
2. Treat Silurian `precipitation_accumulation` as rainfall/precip amount compatible with the app's existing `precipAmountMm` display path.
3. Source checked: Silurian OpenAPI schema at `https://beta.weather.silurian.ai/api/v1/openapi.json`.
