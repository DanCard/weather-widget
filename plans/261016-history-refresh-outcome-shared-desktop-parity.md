# History Refresh outcome: one shared rule, desktop parity

Follows `plans/261016-history-refresh-reports-refused-forecast-part.md` (Android only, not yet
committed). User, 2026-10-16: "Can we have parity between desktop and android and use shared code?"

## Today

| | Android | Desktop |
|---|---|---|
| Tap | "Forecast refresh queued" | "Forecast refresh queued" |
| Done | "Refreshed Google[ · Hourly forecast quota used · resets 12 AM]" (rule in `ForecastHistoryActivity` companion) | nothing |
| Refused part known from | `WidgetStateManager.getProductQuota` / `getRateLimit` | `PRODUCT_QUOTA` / `FETCH_RATE_LIMITED` app_logs rows (`DesktopWeatherDao.getLatestProductQuota` / `getLatestRateLimit`) |
| Failure known from | `fetchSourceOnDemand` = false | the refresh threw (it logs `CURRENT_TEMP_STATUS ok=false` and rethrows) |

## Change

1. **`:shared` `RefreshOutcome`** (`shared/util`, next to `RefreshNotice`): the sealed type
   (`Failed`, `Refreshed`, `Refused(scope, untilMs)`, `HourlyIncomplete`), the rule
   `of(ok, hourlyBlockedUntilMs, dailyBlockedUntilMs, partialRateLimitAtMs, startedAtMs)`,
   `logDetail`, and the English `text(outcome, sourceName, formatTime)` built from `QuotaNoticeText.summary` /
   `RateLimitNoticeText.short`. This moves the code out of the Android activity unchanged.
2. **Android**: `ForecastHistoryActivity` calls `RefreshOutcome.of` and keeps only the localized
   mapping (it has the same strings as now), so no new translations are needed.
3. **Desktop**: after a History Refresh finishes (both `requestSourceRefresh` branches, when
   `origin == "history"`), read the viewed source's product-quota rows (active = `until > now`),
   the partial rate-limit row, and any failure status row since the tap, then call
   `RefreshOutcome.of`. Show `RefreshOutcome.text` on the History window's pill, and log
   `HISTORY_REFRESH_DONE ok=… source=…` plus the same `refused=…` detail as Android.
4. **`RefreshNoticeState.showOutcome`**: the completion message replaces the "queued" pill even
   inside the 4-second throttle, because it answers the same tap rather than stacking a second one.
   It also restarts the four-second timer and writes `REFRESH_TOAST origin=history done=…`.

Not changed: the other Refresh buttons (Settings, Observations). Observations refreshes observations
only, so a forecast quota never applies to it, and Android has no completion message on Settings
either.

## Tests

| # | Test | Kind |
|---|---|---|
| 1–8 | The 8 `refreshOutcome` cases, moved to `:shared` `RefreshOutcomeTest` | unit |
| 9 | `RefreshOutcome.text`: each outcome's English line (desktop) | unit |
| 10 | `logDetail` | unit |
| 11 | Android `refreshOutcomeText` localized lines (existing Robolectric test, now calling shared) | Robolectric |
| 12 | `RefreshNoticeState.showOutcome` replaces a queued notice within 4 s, and resets expiry | unit |
| 13 | Desktop outcome reader: DB with an active `PRODUCT_QUOTA HOURLY` row → `Refused(HOURLY_FORECAST)`; a cleared row (`until=0`) → `Refreshed`; a refresh that threw → `Failed` (it already wrote `CURRENT_TEMP_STATUS ok=false` and rethrew, so no row is read); a rate-limit row before the tap → ignored | integration (DesktopWeatherDao + reader + RefreshOutcome) |
| 14 | Android and desktop give the same English text for the same outcome (Robolectric English strings vs `RefreshOutcome.text`) | Robolectric |

## Verification

- `:shared` `RefreshOutcomeTest` 10/10 (tests 1–10); desktop `HistoryRefreshOutcomeTest` 6/6 (test 13, plus
  another source's block being ignored); `RefreshNoticeStateTest` 7/7 (test 12); Android
  `ForecastHistoryRefreshTextTest` 2/2 (tests 11 + 14: Android English == `RefreshOutcome.text` for all 6 outcomes).
- `scripts/unit-tests.sh`: 5,467 passed.
- Installed on the Fold 4; desktop rebuilt and restarted (`scripts/buildStart-desktop.sh`). A live
  refused path was not forced: the user raised the daily quota to 200, and a forced 429 costs billed requests.
