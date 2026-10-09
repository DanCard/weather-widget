# Day tap past the hourly data: empty hourly view + "Fetching…", Google fetched on demand

Status: **implemented 2026-10-09** (after two design reversals; see "Decisions")

## Report

Desktop daily view, Google Weather displayed. Clicking next Thursday (2026-10-15, +6 days) said the
hourly data was not available. Wanted: a "fetching" message and an empty hourly graph.

## Root cause (evidence)

- `app_logs` 03:23:52: `CLICK_DAILY date=2026-10-15 zone=MAIN_COLUMN_UPPER targetView=TEMPERATURE`.
- `config.json`: `weatherSource=GOOGLE_WEATHER`. Hourly is loaded for the displayed source only.
- Google hourly is `GoogleWeatherApi.FORECAST_HOURS = 72`; the stored rows ended Mon Oct 12 02:00.
- Desktop `onNeedHourlyRefresh` fetched nothing: it answered from memory, so the "pending" banner
  turned into "Results of refresh: No hourly data…" at once, and the view stayed on daily.
- The view stayed on daily because an hourly view with no hours in its window crashed label placement
  (`LabelCandidateCollector.deduplicateAnchors`: index 0 of an empty list). That was the old "black
  screen" the guard hid.

## Decisions (user, 2026-10-09)

1. First proposal: Google 24 h routinely, rest on demand. Approved for Google only. Other sources
   return their whole horizon in one free call, so a cap saves no requests.
2. User then asked for 72 h on every source to save space. Withdrawn: hourly rows drive the day/night
   rain chance and the daily icon's noon cloud shading.
3. Google 24 h withdrawn for the same reason (noon cloud shading for days 2–3). **Final: Google stays
   at 72 h (paging unchanged, `GoogleHourPaging`); only a tapped day past 72 h is fetched on demand.**
   Other sources unchanged.
4. Hourly reach 168 h → **240 h** (Google's maximum, matching its 10-day daily view). On the live
   check, tapping Fri Oct 16 (day +7) fetched only 00:00–03:00, because both loaders and the on-demand
   cap stopped at 168 h; that cleared the banner onto an empty graph. `HourlyOnDemand.REACH_HOURS` now
   drives the Android loaders (`WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS`) and desktop
   `loadCached`. Cost on the desktop DB: about 13 % more hourly rows loaded (Open-Meteo / Silurian
   only). Re-check: Friday came in as 188 h, 8 pages, full day drawn.

## Design

- `:shared` `HourlyOnDemand`
  - `hoursToCover(source, date, now, storedHourly)`: the Google horizon reaching that day's last hour
    (capped at the loaders' 240 h reach). Returns null when fresh rows cover the day, for a non-Google
    source, or for a past day.
  - On-demand rows (past the routine 72 h) count only for 12 h (`MAX_EXTENSION_AGE_MS`), since routine
    fetches never refresh them. The daily prune deletes them once stale (`extensionStartMs`,
    `pruneFetchedBefore`): Android `HistoryPruneWorker`, desktop `pruneHistorySnapshotsIfDue`.
- `GoogleWeatherApi`
  - `getForecast(hoursAhead = 72)`: a deeper horizon takes every page and skips the page-1 check.
  - `getForecastHours(hoursAhead)`: `forecast/hours` only, for the desktop.
- **Desktop** day tap: open the day's hourly view at once (empty), with "Fetching hourly forecast for
  {day}…" held until `DesktopWeatherRepository.extendHourlyFor` finishes. That call fetches, stores
  the live rows and a history snapshot, and logs `HOURLY_ON_DEMAND` and `GOOGLE_HOURS_PAGES …
  trigger=on_demand_day`. Afterwards the banner clears, or shows "No hourly data… data ends …".
  Non-Google with no hours for that day: hourly view plus "No hourly forecast for {day} — data ends …"
  (nothing to fetch).
- **Android** day tap: same view and banner. The existing forced no-hourly sync carries
  `ForecastFetchContext.hourlyAhead` for Google; `handleRefreshComplete` clears the banner or shows
  the result. `HourlyForecastStore` always rewrites on-demand rows so their `fetchedAt` stays current.
- Label engine: `TemperatureLabelEngine.computePlacements` returns no labels for an empty window.
- `scripts/google_usage_today.py`: on-demand `GOOGLE_HOURS_PAGES` rows count toward `forecast/hours`
  only, not `forecast/days`.

## Tests

- `:shared` `HourlyOnDemandTest`; `GoogleWeatherApiTest` (deeper horizon, `getForecastHours`, quota).
- Desktop `DesktopNoHourlyDayClickTest` covers:
  - Google tap → empty hourly view with the "Fetching…" banner;
  - the banner clearing when the hours arrive;
  - the "still missing" result;
  - an already-covered day (no fetch);
  - NWS (no fetch);
  - every hourly graph rendering an empty window.
- Desktop `DesktopHourlyOnDemandTest`: real SQLite fetch-once, plus prune scope (Google only).
- Android `GoogleWeatherFetchIntegrationTest` (on-demand horizon requested);
  `DailyFutureDayNoHourlyClickIntegrationTest` (instrumented: hourly view and "Fetching…" banner).

## Also fixed

- Desktop `hourlyPointsInWindow` / `HourlyGraphInput`: an empty window fell back to the **earliest**
  stored hours and drew them under the tapped day's axis (a dashed, unlabelled curve under the
  "Fetching…" banner). The fallback dates from the desktop scaffold (`ce33401c`); removed. Android
  has no such fallback.

## Open question

- **Prune** (user, 2026-10-09: "why prune?"). The 12 h freshness check already makes taps refetch.
  On-demand rows also give days past 72 h their noon cloud shading in the daily view, so pruning them
  makes that shading come and go. The space involved is tiny. Recommendation: drop the prune and keep
  the freshness check — awaiting the user's answer.
