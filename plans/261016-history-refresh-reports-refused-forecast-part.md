# Forecast History Refresh reports a refused forecast part

## Problem

Fold 4, 2026-10-16 21:02: Forecast History → Refresh on Google. `forecast/days` and
`currentConditions` answered 200, `forecast/hours` got a 429 (`ForecastHoursQueriesPerDay`, 150/day,
exhausted at 17:24 PT; two emulators had spent ~58 of it). `HISTORY_REFRESH_DONE ok=true`, and the
screen toasted **"Refreshed Google"**. The hourly forecast was not refreshed, and nothing on screen
said so: the widget's daily view deliberately hides an hourly-only quota block (user, 2026-10-07), and
the History screen has no banner.

## Root cause

`ForecastHistoryActivity.refreshViewedSource` gets back a single `Boolean` from `fetchSourceOnDemand`:
"the source answered". A fetch where one product was refused still counts as answered (by design,
because the other product was saved), so the toast cannot tell a full refresh from a partial one.

## Fix

The fetch already records exactly what was refused: `ForecastFetchCoordinator.recordProductQuotas`
stores a product quota block (`WidgetStateManager.getProductQuota`), clears it when the product
answers, and records a per-minute partial refusal (`getRateLimit`, `partial=true`). After the
refresh, the activity reads those and adds the refused part to the toast:

| Outcome | Toast |
|---|---|
| failed | Couldn't refresh Google (unchanged) |
| everything answered | Refreshed Google (unchanged) |
| hourly quota-blocked | Refreshed Google · Hourly forecast quota used · resets 12 AM |
| daily quota-blocked | Refreshed Google · Daily forecast quota used · resets 12 AM |
| both blocked (current temp only) | Refreshed Google · Daily quota used · resets 12 AM |
| hour pages cut by a per-minute 429 during this refresh | Refreshed Google · Hourly forecast incomplete |

- The text is built only from existing localized strings (`obs_refreshed_source`,
  `watermark_quota_*`, `watermark_rate_limited_partial`), so no new string needs 19 translations.
- A rate limit counts only if it was recorded after the tap (`atMs ≥ start`). A quota block counts
  whenever it is active: an active block means that product was not refreshed, whether this request
  was refused or the product was never asked for.
- The toast becomes `LENGTH_LONG`, because the message is longer.
- The decision is a pure companion function (`refreshOutcome`), and the activity only maps it to text.
- `HISTORY_REFRESH_DONE` also logs `refused=HOURLY|DAILY|BOTH|HOURLY_PARTIAL`.

Desktop: its History Refresh shows "queued" and no completion message, so it never claims success.
Out of scope.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `refreshOutcome` failed → Failed | unit |
| 2 | ok, no blocks → Refreshed | unit |
| 3 | ok, hourly block → Refused(HOURLY_FORECAST, until) | unit |
| 4 | ok, daily block → Refused(DAILY_FORECAST, until) | unit |
| 5 | ok, both blocked → Refused(SOURCE, earliest until) | unit |
| 6 | ok, partial rate limit after start → HourlyIncomplete | unit |
| 7 | ok, partial rate limit before start → Refreshed | unit |
| 8 | ok, hourly block + partial rate limit → quota wins | unit |
| 9 | `refreshDoneText` maps each outcome to "Refreshed X · …" with the localized pieces | Robolectric |

## Verification

- `ForecastHistoryActivityTest` 33/33 (tests 1–8, plus the log detail); `ForecastHistoryRefreshTextTest`
  4/4 (test 9, Robolectric, English strings).
- `scripts/unit-tests.sh`: 5,461 passed (1 skipped).
- Debug APK installed on the Fold 4. The refused path was not reproduced live: the user raised
  `ForecastHoursQueriesPerDay` to 200 the same evening, so a Refresh now gets its hours, and it was
  not worth spending billed requests to force a 429.
