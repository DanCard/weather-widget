# Manual refresh fetches the FULL hourly window of the current source

User, 2026-10-12: "A manual refresh should trigger a full refresh of the current API."

## Evidence (desktop, 2026-10-12 14:10)

- `REFRESH_ENTER reason=user_refresh:history parts=BOTH source=GOOGLE_WEATHER`
- `forecast/days` 200 → 10 daily rows; Monday 10-19 row rewritten (67/52 → 67/53).
- `GOOGLE_HOURS_PAGES pages=2 … hours=48`: the NEAR window. Monday's noon cloud (92 %) and
  8am–8pm rain max (15 %) were **carried forward** from the 04:16 FULL fetch (`pages=8 hours=192`).
  No Google hourly row past 10-15 was refreshed.

## Root cause

`HourlyWindowPolicy.choose` only knows the routine rule: FULL needs charging (or ≥ 80 %) **and**
20 h since the last FULL at the site. A user Refresh goes through the same rule, so it normally gets
NEAR (48 h). The daily row's far-day summaries come from the hourly download
(`DailyHourlySummaries`), so most of what the user sees for days 3–8 does not change.

## Change

1. `:shared` `HourlyWindowPolicy.choose(…, userRefresh: Boolean = false)`: `userRefresh` → FULL
   regardless of battery or the 20 h marker. A user FULL does **not** write the FULL marker
   (`HOURLY_FULL_FETCH` / `markFullHourlyFetch`). It still writes the hourly-cadence marker
   (`HOURLY_FETCH` / `markHourlyFetch`). Otherwise a 14:00 tap would block the 03:00 charger FULL
   (only 13 h old). If the phone isn't charging again before the next night, the far days would then
   go ~37 h without a refresh, for the sake of saving 8 pages (user, 2026-10-12).
2. **Current source only.** Other sources keep the routine rule.
3. Desktop:
   - `DesktopWeatherRepository.refresh/refreshWithOutcome(userRefresh = true)` from both
     `user_refresh:$origin` call sites in `DesktopUiApplication`.
   - `DesktopHourlyWindow.current(nowMs, userRefresh)`. The service decides how many Google pages to
     ask for, and it reads the window itself today, so the repository passes the chosen window into
     `fetchForecast` (new optional `hourlyWindow` param). That way the service and the repository
     can't disagree.
   - Log `REFRESH_ENTER … window=FULL`.
4. Android:
   - Settings → "Refresh data" (`settings_manual_refresh`): new worker input `KEY_USER_REFRESH` →
     `ForecastFetchContext.userRefresh`. `ForecastFetchCoordinator.hourlyWindow` returns FULL for
     sources in `activeSourceIds` (the displayed one) when it is set.
   - Forecast History Refresh button (`fetchSourceOnDemand`, single source): pass `userRefresh = true`.
   - Not changed: the widget's `ACTION_REFRESH` (also sent by screen-on, unforced cadence check),
     the Observations screen refresh (current temp only) and source switches / location changes.
   - Log `HOURLY_WINDOW source=… window=FULL reason=user_refresh`.

## Cost

Google: FULL = 8 `forecast/hours` pages vs 2, so **+6 billed requests per manual tap** (quota
60/day per project). Free sources: no extra requests; their whole horizon is already downloaded,
and FULL only changes how much feeds the summaries. Hourly storage stays at 72 h either way.
There's no per-tap throttle, at the user's request. If quota becomes a problem, add a cooldown later.

## Tests

- `HourlyWindowPolicyTest`: `userRefresh` → FULL on battery at 20 %, and FULL 1 min after a FULL.
- Desktop repository test (fake service): a user refresh passes `Window.FULL` to `fetchForecast`
  and writes `HOURLY_FETCH` but **not** `HOURLY_FULL_FETCH`, so a charger FULL that is due stays due.
  A non-user refresh on battery passes NEAR.
- Android `ForecastFetchCoordinator` test: `userRefresh` context → FULL for the displayed source,
  NEAR for a non-displayed one on battery.
- Manual: desktop Refresh on Google → `GOOGLE_HOURS_PAGES pages=8 hours=192`. Monday's
  `noonCloudPercent`/`hourlyDayPrecipMax` row `fetchedAt` = the tap.
