# Google hourly quota: hourly-limited refreshes, and per-endpoint `api_usage_stats`

2026-10-08. Cloud Console showed 23 `forecast/hours` calls by ~06:00 PT.

## Findings (since midnight PT, 2026-10-08)

| Client | Full Google fetches | `forecast/hours` requests | Triggers |
|---|---|---|---|
| Desktop | 00:11, 01:51, 02:11, 05:56 | 12 | `network:restored` ×3, cloud-while-viewing (02:11, 20 min after 01:51) |
| Pixel 7 Pro | 00:21, 05:58 | 6 | `missing_actuals_GOOGLE_WEATHER_today`, `stale_on_refresh_action` |
| Samsung Fold | 01:49, 02:29, 05:19, 05:49 | 10 | `toggle_api_stale` ×3, `cloud_while_viewing` |

28 in total; the console lags. No unlogged path: Android's Google current temp is the one-request
`getCurrent`, and no fetch failed after spending pages.

- Every fetch but one logged `reason=tail_short`: `GoogleHourPaging` requires the stored tail to reach
  now+72h−2h, but it ends at *previous fetch*+72h, so any fetch ≥ 2 h after the last pays all 3 pages.
  **Fixed** (user approved): slack = `MAX_TAIL_AGE_MS` (12 h); an unchanged page 1 now stops at 1 page.
- Automatic triggers (screen on / wake / network restore, cloud-while-viewing every 15 min) and
  source cycling each did a full Google fetch.

## User's rules (2026-10-08)

1. Screen on refreshes current temp and actuals, not the hourly forecast.
2. Cycling sources does not refresh the hourly forecast; only while an hourly view is showing may a
   stale source refetch it.
3. The daily forecast is not affected by either.

## Change

- `:shared` `HourlyFetchGate`: a fetch is *hourly-limited* or not. Limited, Google skips
  `forecast/hours` and `history/hours` (`GoogleWeatherApi.getForecast(includeHours = false)`,
  `GOOGLE_HOURS_PAGES pages=0 reason=hourly_limited`) — **unless the stored hourly is itself due by the
  normal cadence**. Cadence is judged from daily rows, so without that a daily-only refresh would starve
  the scheduled hourly fetch. Other sources return daily and hourly in one request; they are unchanged.
- Hourly-limited triggers:
  - Android: `cloud_while_viewing`; source toggle in the daily view (`toggle_api_stale`,
    `stale_on_toggle_api`). Flag rides `KEY_HOURLY_LIMITED` → `WorkInput` → `ForecastFetchContext`.
  - Desktop: wake / network restore catch-ups (`launchHourlyLimited`), `cloud_while_viewing`, and a pure
    source switch (`source_change`, split from `source_or_location_change`) in the daily view.
- Not limited: scheduled cadence, explicit refresh, startup, location change, settings changes, toggle
  in an hourly view.

## Logging

- `GOOGLE_REQUEST endpoint=… status=…` per billed request, both platforms (`GoogleWeatherApi.onRequest`).
- Desktop `REFRESH_ENTER reason=… hourlyLimited=…`; Android `STALE_REFRESH_ENQUEUE … hourlyLimited=…`.
- `api_usage_stats` keyed by (date, apiSource, endpoint) with `errorCount` and `quotaRefusedCount`
  (Room v75 rebuild; desktop schema v28, same DDL `DesktopWeatherDatabase.API_USAGE_STATS_DDL`).
  Endpoint from `:shared` `ApiUsageClassifier` (versions dropped, `:lookup` cut, digit segments →
  `{id}` so coordinates never land in the table). Desktop now has the table too (30-day retention).

Console check: `SELECT endpoint, SUM(callCount) FROM api_usage_stats WHERE apiSource='GOOGLE_WEATHER'
AND date = <today> GROUP BY endpoint` on each client, summed.

## Tests

`ApiUsageClassifierTest`, `HourlyFetchGateTest`, `GoogleHourPagingTest` (tail short by the fetch gap), `GoogleWeatherApiTest` (limited fetch, request hook),
`ApiUsageDaoTest`, `DailyViewApiToggleIntegrationRoboTest`, `DesktopApiUsageAndRefreshGateTest`,
`WeatherDatabaseMigrationTest.migrate74To75…` (instrumented).
