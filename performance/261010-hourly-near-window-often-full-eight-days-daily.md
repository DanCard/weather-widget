# Hourly fetch: near 24 h at the normal cadence, full 8 days once a day while charging

Status: **implemented** (2026-10-10), with the changes under "As implemented" below. Builds on
`performance/261010-daily-view-summaries-instead-of-far-hourly.md` (hourly *stored* to 72 h; far days
live on the `forecasts` row).

## Goal (user, 2026-10-10)

- Fetch **8 days** of hourly **once every 24 h, while charging**.
- Fetch **less than 8 days more often**.
- Applies to **every source**, not Google only.
- Storage is a separate question: it stays 72 h; what the far days need is on the `forecasts` row.

## Today

Hourly rides the forecast fetch: displayed source 240 / 360 min charging, others 480 / 720, battery
tiers off charger (`ForecastCadence`, `BatteryTier`). Every fetch asks for the source's whole routine
horizon and stores 72 h (`HourlyHorizons.ROUTINE_HOURS`). Screen-on / wake / daily-view source cycling
are *hourly-limited* (`HourlyFetchGate`) and skip hourly unless due by that same cadence.

Request shape differs by source, which bounds what a "window" can save:

| Source | Hourly request | Window changes request count? |
|---|---|---|
| Google | `forecast/hours`, billed page per 24 h (`hoursAhead`, already a parameter) | **yes** |
| NWS, Silurian | separate hourly endpoint, whole horizon per call | no (fewer *calls* only) |
| Open-Meteo, Tomorrow.io, OWM, WeatherAPI | same request as daily | no |

## Design

One pure rule in `:shared` (`HourlyWindowPolicy`), used by Android and desktop:

| Window | Hours | When due | Persisted |
|---|---|---|---|
| **NEAR** | 24 | every normal forecast cadence (unchanged gate) | next 24 h hourly + history snapshots |
| **FULL** | 192 (8 d), capped at the source's reach (`HourlyHorizons.maxHours`) | charging (`BatteryTier.treatAsCharging`) **and** last FULL for source+site ≥ 24 h ago | next 72 h hourly + history; summaries for every covered day |

- A fetch that is both due is FULL. Off charger only NEAR is scheduled; a day tap past the stored
  hours still goes through `HourlyOnDemand` unchanged.
- **Summaries:** computed from the whole download before the trim, as now
  (`DailyHourlySummaries.forDate`). A NEAR download covers only the first day or so, so most fields
  come back null and `carryForward` keeps the previous row's value — already the rule, no change.
  FULL is what refreshes days 2–8, so an 8-day reach is what makes daily far-day cloud/rain
  fresh once a day.
- **Google:** NEAR = `hoursAhead=24` (1 page); FULL = `hoursAhead=192` (8 pages). The existing
  "pages 2+ only if page 1 changed" gate stays, so FULL can cost fewer. Charging, displayed,
  screen on: ≤ 6×1 + 8 = 14 pages/day against today's ≤ 18; quota 60/day per project.
- **Free sources:** request unchanged; a NEAR fetch persists 24 h instead of 72 h, so writes and
  `hourly_forecast_history` snapshot depth drop. Hours 24–72 are up to ~24 h old between FULL runs
  (accepted: "less than 8 days more often").
- **Marker:** last FULL fetch per source+site, location-scoped like `ForecastFetchCoordinator.isStale`
  (a new site is FULL-due at once). Stored where each platform already keeps per-source fetch state.

## Interactions to check before coding

1. `HistorySnapshotRetention` and every reader of older snapshots (CLAUDE.md: new readers must be
   added to that rule) — confirm none needs hours 24–72 from *each* run.
2. `HourlyFetchGate`: its "due" now means NEAR-due; FULL-due is judged separately from the marker.
3. `ViewingRefreshPolicy` / `SourceToggleRefreshPolicy` (> 4 h): toggling to a stale source should
   fetch NEAR, not FULL, unless FULL is due.
4. Desktop loops and Android `ForecastFetchCoordinator` both choose the window from the same call.
5. Hourly-view paint with missing hours (`fillHourlyGaps`, KEEP, 15 min): still the one gap-fill; it
   asks for the window that covers the gap.

## Tests

- `HourlyWindowPolicyTest` (shared): charging/off charger, 24 h boundary, new site, reach cap per source.
- Google paging at 24 and 192 h (`GoogleHourPagingTest`): page counts, 429 handling unchanged.
- Summaries: NEAR payload over an existing row keeps far-day cloud/rain; FULL refreshes them.
- One integration test per platform (2+ classes): coordinator → store → forecasts row.
- Desktop/Android parity: same window for the same inputs.

## Not changed

Observations and current-temp cadence, daily forecast cadence, retention table, 72 h storage,
on-demand past 72 h, background source gate.

## As implemented (2026-10-10)

Decisions made after the plan was written:

- **NEAR is 48 h, not 24 h** (user's call). 24 h starves history rules 3 and 4
  (`HistorySnapshotRetention`: a copy captured the previous day for every hour of today) and ages
  tomorrow's rain; see `notes/261010-daily-view-hourly-dependency.md`. Hours 48–72 are up to a day
  old between FULL runs (accepted); day+2's rain summary can lag that much.
- **FULL = what is asked 8 days (192 h) of a billed source; what is stored stays 72 h.** Summaries are
  computed from the whole download before the trim, as before.
- **Google's page-1 gate stays for NEAR only.** FULL asks 192 h, which skips the gate by the
  existing "deeper horizon takes every page" rule (`hoursAhead <= FORECAST_HOURS` now gates it), so a
  FULL fetch is 8 pages, once a day. NEAR is 1–2 pages: 24 h page, then page 2 only if page 1 changed.
- **Charging = `BatteryTier.treatAsCharging`** (charging, or battery ≥ 80 %), the test the forecast
  cadence already uses.
- **Null context or an on-demand day is unchanged**: no window, 72 h as before.
- **Marker:** Android `WidgetStateManager.getLastFullHourlyFetch/markFullHourlyFetch` (one pref per
  source, valid at one site); desktop an `app_logs` row `HOURLY_FULL_FETCH` (72 h retention > 24 h).
  Only a fetch that brought hourly marks FULL (a hourly-limited or quota-refused Google fetch does not).

Code: `:shared` `HourlyWindowPolicy` (rule, marker), `HourlyOnDemand.hoursAhead/askHours(window)`,
`GoogleWeatherApi` (horizon-relative paging gate); Android `ForecastFetchCoordinator` (`hourlyWindow`,
`markFullIfDone`); desktop `DesktopHourlyWindow`, `DesktopWeatherRepository.refresh`,
`DesktopWeatherService` (Google ask). Log: `HOURLY_WINDOW source=… window=FULL` (Android),
`HOURLY_FULL_FETCH` (desktop), `HOURLY_TRIM horizonH=48|72`.

Tests: `HourlyWindowPolicyTest`, `GoogleWeatherApiTest` (+3 paging), `HourlyWindowStorageIntegrationTest`
(Android: coordinator + stores + Room), `DesktopHourlyWindowTest`.

On-device (2026-10-10, emulator, battery 100 %): a forced refresh logged `HOURLY_WINDOW window=FULL`
for NWS, Silurian and Open-Meteo and wrote `hourly_full_fetch_<SOURCE>` markers; Google was
quota-blocked (`pages=0 reason=quota_blocked`) and correctly wrote no marker.

Not verified live: the NEAR path on a device (the Settings refresh would not re-fire within the
session; covered by `HourlyWindowStorageIntegrationTest`), Google's 8-page FULL fetch (quota blocked
until midnight PT), and the desktop `HOURLY_FULL_FETCH` row (desktop displays Google).
Also: `GoogleHourPaging`'s `MAX_TAIL_AGE_MS` 12 h still applies to the 24–48 h tail on NEAR runs.
