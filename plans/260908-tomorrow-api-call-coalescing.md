# Reduce Tomorrow.io calls by combining timelines and coalescing refreshes (2026-09-08)

## Evidence

Tomorrow.io's free plan currently allows 25 requests per hour and 500 per day. Live desktop and
Android logs on 2026-09-08 showed repeated HTTP 429 responses within an hour and recovery after the
hour boundary. The same API key is being exercised by the desktop process, a Pixel, and two running
emulators.

The shared `TomorrowIoApi.getForecast` currently sends two requests to `/v4/timelines`: one for
hourly data and one for daily data. Tomorrow.io supports multiple timesteps in one Timeline request,
so those payloads can be returned by one call.

On desktop, a full Tomorrow.io refresh also requests `/v4/weather/realtime`. The 10-minute
observation loop can trigger that full refresh through the 15-minute cloud-viewing freshness gate,
then immediately request realtime again. That spends one redundant call. Android's full forecast
path does not request realtime, so its later current-temperature request remains distinct and must
not be skipped.

## Changes

1. Send one Tomorrow.io Timeline request with both `1h` and `1d` timesteps and the union of the
   hourly and daily fields. Select returned timelines by their `timestep` value instead of response
   position.
2. Have the desktop repository expose whether a completed full refresh supplied observations.
3. In the desktop observation loop, skip the follow-up observation fetch only when the full refresh
   completed successfully and supplied observations. Keep the decision provider-neutral.
4. Leave standalone observation refreshes unchanged when the full result has no observations or
   the full refresh fails.

## Other providers

- Open-Meteo already requests and parses hourly and daily fields in one forecast call.
- WeatherAPI already receives current, hourly, and daily values from one forecast response.
- OpenWeatherMap derives daily values from its forecast list, but its deprecated/free client still
  uses a separate endpoint for current weather.
- NWS publishes its period forecast and hourly forecast at separate URLs. Its gridpoints bundle can
  combine some grid fields, but it does not replace both forecast products.
- Silurian API2 publishes hourly and daily forecasts at separate endpoints.

Only Tomorrow.io has an unclaimed provider-supported hourly/daily consolidation in the current
clients, so no cross-provider request-builder abstraction is warranted for this change.

## Verification

1. Shared/API tests prove one Timeline request contains both timesteps, preserves the 23-hour
   lookback, and parses both timelines independent of response order.
2. Desktop tests prove a full Tomorrow.io refresh still preserves separate recent-history and
   realtime provenance.
3. Focused shared, desktop, and Android tests and compilation.
4. Rebuild/restart desktop and verify the live log shows a combined full refresh without an
   immediate redundant observation-only request.

## Future follow-up

Add an optional per-provider quota-conservation setting. It can select provider-specific forecast,
current-observation, inactive-source, and retry policies without embedding quota tiers in the
generic scheduler. This is intentionally outside the present two reductions.

## Verification results

- The exact combined request returned HTTP 200 from Tomorrow.io with `1d=6` and `1h=121` intervals.
- The rebuilt desktop app completed a live refresh with `hourly=121 daily=6 obs=25`.
- `TomorrowIoApiTest`, `TomorrowIoDesktopServiceTest`, `DesktopRefreshObservationsTest`, and
  `DesktopBackfillIntegrationTest` passed.
- All shared Short tests and all desktop Short tests passed.
- Android compiled, assembled, installed on `emulator-5554`, and completed a widget cache repaint.
