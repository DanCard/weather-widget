# Observation loop: fetch a recent window, not a single latest row (2026-08-20)

## Problem

`2befc157` ("Make desktop observation loop fetch latest-only, not 7-day history") replaced the
per-cycle 7-day history window with a single `/stations/{id}/observations/latest` lookup. That
removed the intended ~2500 rows/cycle of redundant work, but it also went one step too far: a
station publishing faster than the poll interval now has its intermediate observations discarded.

Confirmed on the live DB (2026-08-20). KSJC publishes roughly every 5 minutes; the loop polls every
10. Two gaps >6 min exist in the last 48h of `observations`, both since the change went live:

| Missing (local) | In NWS API? |
|---|---|
| 10:10 | No — station genuinely silent (no `17:10Z` feature) |
| 10:30 | **Yes** — `2026-08-20T17:30:00+00:00`, 19 °C, absent from our DB |

The `fetchedAt` column separates the two mechanisms cleanly: every row at or before 10:20 carries
`fetchedAt=10:32:24` (the hourly full pull's bulk backfill), while 10:25 and 10:35 carry their own
distinct poll timestamps — those are the loop's single-row fetches, and 10:30 fell between them.

Secondary finding: `/observations/latest` is *laggier* than the list endpoint. The 10:48:36 poll
returned a 10:25 observation, while the list endpoint already had 10:45 available at that time. So
the single-row fetch costs freshness as well as coverage.

### Why it is not worse today

`refresh()`'s hourly full pull re-fetches the whole 7-day window, so holes heal within ~1h of AC
uptime. The defect is a transient hole in the actual curve, not permanent data loss — but the hole
widens directly with the poll interval, which matters given the proposed 30-minute screen-off tier.

## Fix

Fetch a **short recent window** instead of a single row: enough to cover the poll interval plus the
endpoint's publish lag, small enough to keep the CPU win that motivated the original change.

1. `DesktopWeatherService.fetchObservationBundles` — in the recent-only branch, call
   `nwsApi.getObservations(station.id, now - RECENT_WINDOW_MINUTES, now)` instead of skipping the
   window. The existing tri-state `historicalOutcome` handling then applies unchanged, which also
   retires the `historicalOutcome!!` the flag introduced.
2. `RECENT_OBSERVATION_WINDOW_MINUTES = 90`. Covers a 30-minute screen-off poll + the ~25-minute
   observed publish lag + one missed cycle. ~18 rows for KSJC, ~50–90 rows/cycle across 5 stations,
   versus ~2500 for the 7-day window — the reduction that motivated `2befc157` survives intact.
3. Rename `latestOnly` → `recentOnly` across `WeatherApiClient`, `DesktopWeatherService`, and
   `DesktopWeatherRepository`. The parameter no longer fetches only the latest.

Unchanged: the full-pull path, the Synoptic/web prefer-newest merge that anchors current temp, and
the hourly 7-day backfill that remains the long-gap safety net (daemon down, suspend, missed cycles).

## Tests

- `DesktopObservationLatestOnlyTest` → `DesktopObservationRecentWindowTest`. The old assertions
  (`exactly = 0` calls to `getObservations`, exactly 2 rows returned) encode the bug, so they invert:
  one `getObservations` per station, a window of ~90 minutes rather than 7 days, and **every**
  observation in that window returned — the regression test for the dropped 10:30 row.
- `DesktopObservationCpuTest`'s 500ms process-CPU bound stays as the guard that the full pull has not
  crept back in.

## Verification

- `./gradlew :desktop:testByDurationDesktop`
- Post-restart: no gaps in `observations` for KSJC beyond the station's own publish cadence, checked
  against `api.weather.gov` for the same window.
