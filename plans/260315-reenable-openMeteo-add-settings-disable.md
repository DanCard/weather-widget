# Re-enable Open-Meteo and Add True API Disable in Settings

## Summary
- Remove the runtime rule that hides Open-Meteo whenever NWS coverage exists.
- Treat the Settings API source list as the single source of truth for which providers are enabled and in what order they appear.
- Make disabled sources a true disable: no widget/source toggle entry and no forecast/current-temperature fetches until re-enabled.

## Key Changes
- `WidgetStateManager`
  - Stop filtering `OPEN_METEO` out of the effective visible source list for NWS-covered locations.
  - Keep `visible_sources_order` as the canonical enabled-and-ordered source list.
- `SettingsActivity`
  - Always show all configurable APIs in the ordered checkbox list, including Open-Meteo.
  - Keep the existing “at least one source enabled” rule and reordering behavior.
  - Update settings copy so it no longer claims hidden sources still fetch in the background.
- `ForecastRepository`
  - Only consider enabled sources for stale checks and forecast fetches.
  - Remove the NWS-coverage special case that blocked Open-Meteo forecast fetches.
  - If a targeted refresh explicitly requests a disabled source, return cached data without fetching it.
  - Skip uncached Open-Meteo climate-normal fetches when Open-Meteo is disabled; use cached normals if available.
- `CurrentTempRepository`
  - Only fetch current temperatures for enabled sources.
  - Remove the NWS-coverage special case that stripped Open-Meteo from the target list.
  - If an explicit source refresh targets a disabled source, return success with zero fetched sources.

## Test Plan
- `WidgetStateManagerTest`
  - effective source order preserves Open-Meteo when it is enabled.
  - toggle cycling still follows the enabled source order.
- `WeatherRepositoryTest`
  - implicit current-temp refresh fetches Open-Meteo when it is the only enabled source.
  - explicit current-temp refresh skips a disabled Open-Meteo source.
  - targeted forecast refresh skips a disabled Open-Meteo source.

## Assumptions
- API disable applies to all configurable APIs, not just Open-Meteo.
- “Disabled” means no normal fetches and no charging/background fetches for that source.
- Existing stored source order should remain valid with no preference migration changes.
