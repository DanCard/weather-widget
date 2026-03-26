# Add Per-Day Daily Icon Click Zones To Daily Forecast

## Summary
Add a separate per-day icon-stack set of click zones to the tall daily forecast layout. These zones sit under the existing full-height day-column tap targets and route by the icon the user tapped using this logic:

1. Past day icon tap opens history
2. Today/future rainy day opens `PRECIPITATION`
3. Today/future non-rain day whose rendered icon has a cloud opens `CLOUD_COVER`
4. Otherwise it opens `TEMPERATURE`

This keeps the current full-column daily tap behavior intact and adds a separate icon/low-temp-stack interaction layer.

## Key Changes
- Update `widget_weather.xml` to add a horizontal per-day icon-zone container for daily graph mode with `graph_bottom_day1_zone` through `graph_bottom_day10_zone`
- Reserve enough vertical space so the full-column day overlays stop above the rendered icon stack and the icon zones own that band
- Keep the existing shared `graph_bottom_zone` for hourly temperature / precipitation / cloud-cover views unchanged
- Extend `DailyViewHandler` with a `setupGraphBottomDayClickHandlers()` method that mirrors the existing per-column alignment logic after `displayDays` is finalized
- Extend the daily click intent builder so icon-zone taps can override the target hourly mode while reusing the existing history and offset behavior
- Add a helper-driven icon-zone routing decision:
  - rain -> `PRECIPITATION`
  - cloud-family icon without rain -> `CLOUD_COVER`
  - otherwise -> `TEMPERATURE`
- Reuse the existing daily graph data (`date`, `hasRainForecast`, `iconRes`, `columnIndex`) without adding new fetches or state

## Test Plan
- Add unit coverage for the icon-zone routing helper:
  - rainy day -> `PRECIPITATION`
  - non-rain partly cloudy / mostly cloudy / fully cloudy -> `CLOUD_COVER`
  - non-rain clear -> `TEMPERATURE`
- Extend `DailyViewHandlerIntentContractTest` to verify icon-zone intent construction for:
  - past day -> history
  - today cloudy icon -> `CLOUD_COVER` with offset `0`
  - future rainy icon -> `PRECIPITATION`
  - future clear icon -> `TEMPERATURE`
- Extend `DailyViewGraphClickAlignmentTest` so icon zones:
  - align with the displayed day columns
  - hide when the column is unused
  - continue to map sparse data sets to sequential visible columns

## Assumptions
- The new icon zones apply only to the tall daily forecast layout
- Past-day icon taps should open history, matching existing past-day column taps
- Fog-derived mixed/cloud icons should route to `CLOUD_COVER`
