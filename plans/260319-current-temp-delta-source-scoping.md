# Preserve Current Temp Across API and View Changes

## Summary
- Scope current temperature delta state by widget and weather source instead of a single widget-wide slot.
- Keep delta decay behavior unchanged.
- Stop clearing delta state during API toggles; source isolation prevents cross-source reuse.
- Preserve compatibility by reading legacy widget-wide keys and migrating matching state into source-scoped keys.

## Implementation
- Update `WidgetStateManager` current-temp delta helpers to accept a `WeatherSource`.
- Store source-scoped keys with a `widgetId + source.id` suffix.
- Keep legacy keys as a fallback read path only.
- Migrate legacy state on first matching read, then clear the old keys.
- Update `DailyViewHandler`, `TemperatureViewHandler`, `PrecipViewHandler`, and `CloudCoverViewHandler` to read/write/clear delta state with the active source.
- Remove API-toggle clearing from `setCurrentDisplaySource()` and `toggleDisplaySource()`.

## Tests
- Update `WidgetStateManagerTest` so API toggles no longer expect delta removal.
- Add tests for source-scoped delta persistence and legacy migration.
- Keep resolver decay tests unchanged; the bug is state lifetime, not resolver math.

## Acceptance
- Returning to the same API after cycling through others preserves the same source-specific current-temp delta.
- Switching from daily to hourly temperature view does not introduce a multi-degree current-temp jump for the same source unless new observation data arrived.
