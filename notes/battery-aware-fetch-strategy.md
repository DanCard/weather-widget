# Battery-Aware API Fetch Strategy

Updated: 2026-02-24

## Background Scheduled Fetches (WeatherWidgetWorker)

The one-shot WorkManager chain in `scheduleNextUpdate()` adjusts interval based on battery state. Below 50%, no one-shot work is scheduled at all — the periodic 1-hour baseline from `schedulePeriodicUpdate()` re-evaluates and resumes the chain when conditions improve.

| Battery State       | Fetch Interval |
|---------------------|----------------|
| Charging            | 60 min         |
| > 70%               | 120 min        |
| 50–70%              | 240 min        |
| < 50%               | None (skipped) |

When skipped, logcat shows: `BATTERY_SAVE: Below 50%, skipping scheduled update`

## User Interaction Fetches (WidgetIntentRouter)

Most widget taps (navigation, view toggles, zoom) render from cached DB data with **no network call**. A staleness check triggers a background fetch when data is over 4 hours old:

| Interaction                          | Network Fetch?                              |
|--------------------------------------|---------------------------------------------|
| Navigation arrows (left/right)       | Only if data > 4h old                       |
| Toggle view mode (hourly/daily/etc.) | Only if data > 4h old                       |
| Toggle precip view                   | Only if data > 4h old                       |
| Cycle zoom                           | Only if data > 4h old                       |
| Resize widget                        | Only if data > 4h old                       |
| Toggle API source (data cached)      | Only if data > 4h old                       |
| Toggle API source (data **missing**) | Yes (forced refresh) + staleness check      |

The staleness check (`refreshIfStale` in `WidgetIntentRouter`) compares `latestWeather.fetchedAt` against a 4-hour threshold (`STALE_DATA_THRESHOLD_MS`). When triggered, it enqueues a forced refresh via WorkManager — the UI still renders immediately from cache.

## Global Rate Limit (WeatherRepository)

All fetch paths — scheduled, user-triggered, screen-unlock — are subject to a 10-minute minimum interval (`MIN_NETWORK_INTERVAL_MS = 600_000L`) in `WeatherRepository`. This prevents burst fetches regardless of battery state.

## Summary: Fetch Sources by Battery Level

| Battery State | Scheduled Fetches | User Tap (data fresh) | User Tap (data > 4h) |
|---------------|-------------------|-----------------------|----------------------|
| Charging      | Every 60 min      | No fetch              | Background fetch     |
| > 70%         | Every 120 min     | No fetch              | Background fetch     |
| 50–70%        | Every 240 min     | No fetch              | Background fetch     |
| < 50%         | None              | No fetch              | Background fetch     |

## Key Files

- `WeatherWidgetWorker.kt` — `getUpdateIntervalMinutes()`, `scheduleNextUpdate()`
- `WidgetIntentRouter.kt` — `refreshIfStale()`, `STALE_DATA_THRESHOLD_MS`
- `WeatherRepository.kt` — `MIN_NETWORK_INTERVAL_MS`, rate limiting logic
- `WeatherWidgetProvider.kt` — `schedulePeriodicUpdate()` (1-hour baseline)
