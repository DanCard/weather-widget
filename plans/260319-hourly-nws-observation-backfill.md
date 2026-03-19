# Hourly NWS Observation Backfill For Temperature History

## Summary

Add a targeted recent-history NWS observation backfill path for the widget temperature graph.

When the widget renders NWS hourly history and local observation coverage looks sparse, it should enqueue a worker-driven recent-observations backfill. The worker should fetch a bounded recent history window from nearby NWS observation stations, store those rows, recompute daily extremes for affected dates, and refresh widgets from cache when complete.

## Key Changes

- Add a dedicated recent NWS observation backfill method in `ObservationRepository`.
  - Fetch the last `12 hours` of observations from nearby NWS stations.
  - Insert/upsert all returned observations into `observations`.
  - Recompute daily extremes for affected dates from stored observations.
  - Log request, station attempts, returned row counts, and completion summary.

- Add a targeted worker mode in `WeatherWidgetWorker`.
  - New worker input keys identify an observation-backfill-only run.
  - This mode skips the normal full sync path.
  - After backfill, refresh widgets from cache so the graph updates automatically.

- Trigger the backfill from the widget temperature graph render path in `TemperatureViewHandler`.
  - Only for `NWS`.
  - Use a cooldown via `WidgetStateManager` so this does not enqueue on every render.
  - Trigger when local observation coverage appears sparse for the visible window.
  - Log both enqueue and skip reasons to app logs.

## Internal Interfaces

- New `WeatherWidgetWorker` input keys for targeted observation backfill mode and explicit latitude/longitude.
- New `WeatherRepository` / `ObservationRepository` method for bounded recent NWS observation backfill.
- New internal helper in `TemperatureViewHandler` to evaluate whether hourly backfill should be requested.

No schema change and no external API change.

## Test Plan

- Add `TemperatureViewHandler` tests for backfill trigger decisions:
  - request when NWS coverage is sparse
  - skip when coverage is dense
  - skip for non-NWS sources

- Run targeted hourly actuals tests covering interpolation/extrapolation plus the new trigger helper.
- Run the full `app:testDebugUnitTest` suite to catch regressions in worker/repository/widget flows.

## Assumptions

- The actual hourly temperature history graph is the widget graph, so backfill is implemented there rather than in `ForecastHistoryActivity`.
- Backfill horizon is `12 hours`.
- Backfill is worker-driven and auto-refreshes widgets on completion.
- Existing interpolation and extrapolation remain as fallback behavior when local history is still incomplete.
