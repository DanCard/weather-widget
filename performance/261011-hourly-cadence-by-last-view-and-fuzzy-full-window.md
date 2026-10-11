# Hourly cadence by last view; FULL hourly window due after 20 h

User, 2026-10-11. **Hourly forecast only.** Every other scheduled fetch — daily forecast, current
temperature, actuals/observations — keeps today's cadence (`ForecastCadence`) and today's 8-day
background gate (`SourceFetchGate`), unchanged.

## New hourly rule (`:shared` `HourlyCadence`)

"Charging" keeps today's meaning: plugged in, or battery ≥ 80 % (`BatteryTier.treatAsCharging`).
"Viewed" = the user switched to the source (any trigger already counted by `source_view_days`).

| Battery | Displayed source | Other sources (by time since last view) |
|---|---|---|
| Charging | 4 h | ≤ 24 h → 4 h · ≤ 36 h → 6 h · ≤ 48 h → 8 h · ≤ 72 h → 12 h · older → none |
| > 50 % | 8 h | ≤ 24 h → 8 h · older → none |
| ≤ 50 % | 24 h | none |

- "none" = no routine hourly fetch. On-demand hourly (day tap / pan past stored hours, the one
  gap-fill, switching to a source while an hourly view shows, explicit Refresh) is unchanged and never
  asks this rule.
- Due = elapsed time since the newest `fetchedAt` of the source's stored hourly rows at the site ≥ the
  interval (same 2-min grace as `ForecastFetchPolicy.isDue`). No stored hourly → due.
- Grace: while view tracking is < 3 days old, a never-viewed source counts as viewed 72 h ago.
- FULL vs NEAR window (`HourlyWindowPolicy`) is unchanged except the interval below.

## How hourly is fetched once due

Each scheduled tick (Android periodic worker; desktop forecast loops) judges each source twice:
**daily due** (today's rule, unchanged) and **hourly due** (new rule).

| daily due | hourly due | What runs |
|---|---|---|
| yes | yes | today's fetch, with hours |
| yes | no | today's fetch, **without** hours |
| no | yes | **hourly-only** fetch for that source |
| no | no | nothing |

Per source, what "without hours" and "hourly-only" mean (checked 2026-10-11):

| Source | Without hours | Hourly-only | Saves |
|---|---|---|---|
| Google | skip `forecast/hours` (already: `includeHours`) | only `forecast/hours` (+ `currentConditions` untouched) | 1–8 billed pages |
| NWS | skip `/forecast/hourly` | only `/forecast/hourly` | a request |
| Silurian | skip `/forecast/hourly` | only `/forecast/hourly` | a request |
| Open-Meteo | drop the `hourly=` parameter | drop `daily=` | payload (16 days × 24 h × 8 variables) |
| Tomorrow.io | `timesteps=1d` only | `timesteps=1h` only | payload |
| WeatherAPI | add `hour=12` (docs: "restricting forecast … output to a specific hour in a given day") | full request, store hourly rows only | payload, not calls |
| OpenWeatherMap | not possible — daily is derived from the 3-hourly list | full request, store hourly rows only | nothing |

- **When daily and hourly are both due, it stays one request** wherever the API combines them
  (Open-Meteo, Tomorrow.io, WeatherAPI, OWM) — splitting a free source into two calls only adds calls.
- **Open-Meteo keeps `current` and `minutely_15` in every fetch, either mode.** `minutely_15` is not
  hourly forecast: it is parsed into `RawFetch.subHourly` (`parseSubHourlyHistory`) and feeds actuals /
  current temperature, which stay on today's cadence.
- Tomorrow.io's 5-minute history request (actuals) is a separate call and is unchanged.
- Hourly-only on WeatherAPI / OWM (no way to omit daily) writes the hourly rows only and leaves the
  daily rows as they are, so their daily data stays on today's cadence as asked. A daily fetch on OWM
  keeps storing the hours it necessarily received.
- The hourly-only fetch reuses the single-source path on-demand already uses
  (`fetchSourceOnDemand` / `HourlyOnDemandWorker` on Android, the desktop equivalent), in quiet mode.
- `HourlyFetchGate` (screen-on / wake / daily-view toggle skip hours unless due) now judges "due"
  with `HourlyCadence` instead of the forecast cadence. One definition of "hourly due".
- Row summaries (noon cloud, rain maxima on `forecasts`) are only computed from a download that has
  hours; a fetch without hours carries them forward, as a NEAR fetch already does.

## Last-view timestamp

`source_view_days` stays per-day with no timestamps. Hour windows (24 h, 36 h) need a time, so add one
value per source, overwritten on every view — the last view, not a history:

- New table `source_last_view (sourceId TEXT PRIMARY KEY, lastViewedAtMs INTEGER)` on both platforms
  (Room 80 / desktop 33, real migration), written beside the `source_view_days` record (Android
  `SourceViewRecorder`, desktop `recordSourceSwitch`).
- No timestamp yet (views before this change): use the newest `source_view_days` date, taken as the
  **end** of that day — errs toward fetching.
- One row per source; no retention needed.

## FULL hourly window: due after 20 h

`HourlyWindowPolicy.FULL_INTERVAL_MS` 24 h → 20 h, so a phone that charges nightly around 3 am gets the
8-day download every night; a run at 03:10 no longer blocks 03:00 the next night. Still at most one
per night (charging tick 60 min, a nightly charge is far shorter than 20 h).

## Code

- `:shared`: new `HourlyCadence` (interval or null) and `SourceLastView`; `HourlyFetchGate` uses
  `HourlyCadence`; `HourlyWindowPolicy.FULL_INTERVAL_MS` = 20 h.
- `:shared` API clients: one `ForecastParts` argument (DAILY, HOURLY, BOTH) on every client, replacing
  Google's `includeHours`; per-source meaning as in the table above. Daily-only / hourly-only writes
  skip the rows the fetch was not asked for.
- Android: `ForecastFetchCoordinator` (per-source daily/hourly due), the periodic worker's tick
  (enqueue hourly-only work for hourly-due-only sources), `SourceViewRecorder`, Room entity/DAO +
  migration 79→80.
- Desktop: `DesktopWeatherService` (`googleIncludeHours` → all three split sources), forecast loops,
  `DesktopWeatherDao` + schema 32→33.
- CLAUDE.md: "Routine hourly fetch" paragraph, "Hourly-limited refreshes", schema version line.

## Tests

- `HourlyCadenceTest`: every table cell; boundaries at 24 h / 36 h / 48 h / 72 h exactly and +1 min;
  battery 50 / 51 / 79 / 80; grace on/off.
- `SourceLastViewTest`: timestamp wins; day-row fallback = end of day; none → null.
- Due-matrix test (shared): the four daily-due × hourly-due rows → action.
- `HourlyWindowPolicyTest`: FULL at 20 h, not 19 h 59 m; 03:10 → 03:00 next night.
- `HourlyFetchGateTest` updated to `HourlyCadence`.
- Per client (request-capture tests, no network): DAILY sends no hourly request/parameter (Google, NWS,
  Silurian, Open-Meteo `hourly=` absent but `minutely_15` + `current` present, Tomorrow.io `1d` only,
  WeatherAPI `hour=12`); HOURLY sends no daily request/parameter where the API allows; BOTH is one
  request on combined sources.
- Hourly-only on WeatherAPI / OWM writes hourly rows and no daily rows.
- Room 79→80 migration test; desktop 32→33 schema test.
- Integration: record a switch → `source_last_view` row → hourly due for that source → hourly-only
  fetch runs (Android coordinator + recorder; desktop service + DAO).

## Logging

`HOURLY_CADENCE source=NWS interval=12h view=60h lastHourly=13h due=true action=hourly_only`, one line
per source per tick.

## As built (2026-10-11) — changes from the plan above

- **WeatherAPI does not split** (no `hour=12`): its past hours feed its actuals line, so a daily-only
  request would freeze today's actuals between hourly fetches. It joins OWM: always the full request;
  daily-only stores everything as before, hourly-only stores the hours only.
- **NWS splits** (user's call after the code check): daily-only skips `/forecast/hourly`, so
  `NwsDailyMapper.assemble` has no hourly cross-check/repair between hourly fetches; gridpoints are
  fetched either way. Desktop's header fallback interpolates the stored hours on a daily-only fetch.
  Hourly-only skips `/forecast` and the station observations.
- **Google hourly-only also skips `currentConditions`** (current keeps the daily cadence).
- **Hourly due inside a FULL window takes daily too** (`ForecastParts.withFullWindow`): the far-day
  summaries FULL exists for live on the daily rows, and an hourly-only fetch writes none.
- **Actuals that ride a fetch stay on the daily cadence**: Tomorrow.io's 5-minute history, WeatherAPI's
  history backfill and NWS's daily-actuals pull run only when the plan includes daily.
- **"Due" is measured from a marker**, not the hourly rows' `fetchedAt` (the Android save skips
  unchanged rows): Android prefs `hourly_fetch_marker_<source>` (site-scoped like the FULL marker),
  desktop `HOURLY_FETCH` app_logs row. Set whenever a fetch brings hours home.
- **Shared, not per platform**: `ForecastParts.forRoutine` / `forForced` (the plan), `requested`
  (what a non-splitting source asks), `planLogLine` (`FETCH_PLAN`), `HourlyCadence.decide`,
  `SourceLastView.recency`. `HourlyFetchGate` is deleted — `forForced` replaces it for every source.
- **Desktop forecast loop** checks every 15 min (`FORECAST_LOOP_TICK_MS`) instead of sleeping one
  daily interval; daily due is still "last `REFRESH` older than `ForecastCadence`". An hourly-only
  refresh logs `REFRESH_HOURLY`, never `REFRESH`, so it cannot reset the daily age.
- Tests: `HourlyCadenceTest`, `SourceLastViewTest`, `ForecastPartsTest`, `ForecastPartsRequestTest`,
  `NwsForecastFetchTest` (parts), `GoogleWeatherApiTest` (daily-/hourly-only), `HourlyWindowPolicyTest`
  (20 h), Android `HourlyCadencePlanRoboTest`, Room `migrate79To80`, desktop `DesktopHourlyCadenceTest`,
  `DesktopHourlyWindowTest` (20 h).
