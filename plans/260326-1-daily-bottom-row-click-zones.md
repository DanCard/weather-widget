# Add Per-Day Bottom Row Click Zones To Daily Forecast

## Summary
Add a separate bottom-row set of per-day click zones to the tall daily forecast layout. These zones sit under the existing full-height day-column tap targets and route by day using this logic:

1. Past day bottom tap opens history
2. Today/future rainy day opens `PRECIPITATION`
3. Today/future non-rain day that is partly cloudy / mostly cloudy / cloudy opens `CLOUD_COVER`
4. Otherwise it opens `TEMPERATURE`

This keeps the current full-column daily tap behavior intact and adds a more precise bottom-row interaction layer.

## Key Changes
- Update `widget_weather.xml` to add a horizontal bottom-row container for daily graph mode with `graph_bottom_day1_zone` through `graph_bottom_day10_zone`
- Keep the existing shared `graph_bottom_zone` for hourly temperature / precipitation / cloud-cover views unchanged
- Extend `DailyViewHandler` with a `setupGraphBottomDayClickHandlers()` method that mirrors the existing per-column alignment logic after `displayDays` is finalized
- Extend the daily click intent builder so bottom-row taps can override the target hourly mode while reusing the existing history and offset behavior
- Add a helper-driven bottom-row routing decision:
  - rain -> `PRECIPITATION`
  - cloud-family icon without rain -> `CLOUD_COVER`
  - otherwise -> `TEMPERATURE`
- Reuse the existing daily graph data (`date`, `hasRainForecast`, `iconRes`, `columnIndex`) without adding new fetches or state

## Test Plan
- Add unit coverage for the bottom-row routing helper:
  - rainy day -> `PRECIPITATION`
  - non-rain partly cloudy / mostly cloudy / fully cloudy -> `CLOUD_COVER`
  - non-rain clear -> `TEMPERATURE`
- Extend `DailyViewHandlerIntentContractTest` to verify bottom-row intent construction for:
  - past day -> history
  - today cloudy day -> `CLOUD_COVER` with offset `0`
  - future rainy day -> `PRECIPITATION`
  - future clear day -> `TEMPERATURE`
- Extend `DailyViewGraphClickAlignmentTest` so bottom-row zones:
  - align with the displayed day columns
  - hide when the column is unused
  - continue to map sparse data sets to sequential visible columns

## Assumptions
- The new bottom-row zones apply only to the tall daily forecast layout
- Past-day bottom-row taps should open history, matching existing past-day column taps
- Fog-derived mixed/cloud icons should route to `CLOUD_COVER`
