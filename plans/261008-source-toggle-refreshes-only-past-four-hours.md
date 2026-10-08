# Source toggle refreshes only past four hours

2026-10-08. User: "If user is toggling API sources make sure currently viewed source data is not more
than 4 hours old" — the daily view and every hourly view (temperature, cloud, rain) alike.

## Before

| Platform | Toggle rule | Threshold |
|---|---|---|
| Android | `SourceStalenessProbe.sourceNeedsRefresh`: missing data, or newest fetch older than | 15 min |
| Android | every interaction's `requestIfStale` (`BatteryFetchStrategy.STALE_DATA_THRESHOLD_MS`) | 4 h |
| Desktop | `source_change` launch refresh (`FORECAST_FRESHNESS_THRESHOLD_MS`) | 15 min |

The 15-minute toggle rule was the stricter, so it decided: on 2026-10-08 the Samsung's toggling at
01:49, 02:29 and 05:49 refetched Google each time (3 `forecast/hours` pages per fetch).

## Change

- `:shared` `SourceToggleRefreshPolicy.STALE_MS` = 4 h, used by Android
  `SourceStalenessProbe.TOGGLE_REFRESH_STALE_MS` and desktop `launchForecastStaleAfterMs`
  (`source_change`).
- Unchanged: a source with nothing to draw fetches at once (Android missing-data arm, desktop
  `cachePresent`); daily-view toggles stay hourly-limited, hourly-view toggles full; desktop startup
  and location change keep 15 min.

## Tests

`SourceNeedsRefreshTest` (threshold, 40-min-old source not refetched), `DesktopApiUsageAndRefreshGateTest`
(`source_change` at 40 min → none, at 4 h → full, no cache → full).
