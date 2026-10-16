# Google: keep the hour pages that arrived when a later page fails

Status: **approved** (user, 2026-10-16: "Do #1").

## Problem

Desktop Refresh on Google, 2026-10-16 14:42: a LONG fetch asks `forecast/hours` for 192 h (8 pages
of 24 h). Pages 1–6 answered 200; page 7 got a 429 and the whole fetch failed
(`REFRESH_CLICK … refresh failed`), so the six good pages — days 1–6 — were thrown away and nothing
was stored.

The 429 was **per minute**, not daily: `quota_limit=ForecastHoursQueriesPerUser`,
`quota_unit=1/min/{project}/{user}`, `quota_limit_value=10`. The project's daily hours quota is 150
(118 used per the Cloud console). An 8-page LONG fetch sends its pages within ~2 s, so it is one or two
other requests away from that per-minute limit.

## Root cause

`GoogleWeatherApi.fetchForecastHours` loops over pages with no catch: any page's failure propagates
out of `getForecast`, discarding the pages already parsed.

## Change

`fetchForecastHours`: when page 2 or later fails with an `ApiAccessException` or an `IOException`,
stop paging and return the pages already received. Page 1 failing still fails as today (nothing to
keep).

- A **daily**-quota 429 on a later page also blocks `forecast/hours` until midnight PT exactly as a
  page-1 one does (shared helper with `fetchProduct`), so the result carries the hourly refusal and
  the banner shows; the hours that arrived are still returned and stored.
- A per-minute 429 / other error: no block; the next fetch tries again.
- `lastHoursPaging` gains `partial=<status|io>`, e.g. `pages=6 reason=no_stored_hours_given hours=192 partial=429`.

Downstream needs nothing: the hourly save writes the hours present (later hours keep what was stored),
and `DailyHourlySummaries` only computes a day's fields from windows the payload covers — the rest
are carried forward.

Not in scope (not asked): pacing pages to stay under 10/min, or not marking LONG on a partial fetch.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | Page 3 per-minute 429 → result has pages 1–2 (48 h), no exception, no quota block; `lastHoursPaging` ends `partial=429` | unit (:shared, recorded fixtures) |
| 2 | Page 3 daily-quota 429 → pages 1–2 returned, `quotaRefused` has HOURLY, next fetch makes no hours request | unit |
| 3 | Page 2 IOException → page 1 returned | unit |
| 4 | Page 1 per-minute 429 → still throws `ApiAccessException` (existing test stays green) | unit |
| 5 | Daily forecast and current conditions unaffected by a partial hourly | unit |

## Verification

- Tests 1–5 in `GoogleWeatherApiTest` (35/35 green); full `:shared`, `:app` unit suites green.
- `:desktop:test` has one failure, `DesktopNwsWebLegTest` "a stale NWS API is overtaken by NWS's own
  METAR text" (expected 70.7, was 68.0). It fails identically with this change stashed and passed at
  ~13:50 the same day: pre-existing and time-of-day dependent, unrelated.
- Installed on the Fold; desktop rebuilt and restarted 2026-10-16 14:49 (2 processes). Live
  confirmation needs a later-page failure, which cannot be forced without hitting the per-minute limit.
