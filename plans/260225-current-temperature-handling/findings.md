# Findings

- Current temp rendering now consistently resolves through a single path (`CurrentTemperatureResolver`) for daily/temperature/precip handlers.
- Source behavior is explicit: interpolation is done for selected source with generic-gap fallback via `TemperatureInterpolator` source argument.
- API-provided current temp is now treated as observed fallback input, separate from interpolated estimate in resolution result.
- Stale-aware display is implemented by lowering precision (no decimal) when estimate comes from hourly data fetched more than 2 hours ago.
- Refresh decision branching is centralized in `WidgetRefreshPolicy`, reducing duplicated conditional logic.
