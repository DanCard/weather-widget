# Daily view keeps its far-day cloud and rain on the forecast row; hourly stored to 72 h

Status: **implemented** (2026-10-10). Replaces
`plans/261009-hourly-horizon-by-viewing-frequency.md` (marked superseded). Brainstorm:
`notes/261010-hourly-space-saving-brainstorm.md`.

## Goal

Keep everything the daily forecast view draws (day/night rain %, noon cloud for the bar split and
the icon) for every forecast day, while storing hourly rows only out to 72 h. **A fetch that does
not reach a day must never blank what an earlier fetch saved for it** (user, 2026-10-10).

## Measured (2026-10-10)

| DB | File | Hourly tables (+ indexes) | Hourly rows fetched > 72 h ahead of their hour |
|---|---|---|---|
| Pixel 7 Pro | 72 MB | ~52 MB (live 17, history 36) | live 66.7k / 107k (62 %), history 68k / 158k (43 %) |
| Desktop | 29 MB | ~5 MB | live 5.4k / 11.4k (47 %), history 7.0k / 24.4k (29 %) |

The Pixel's bulk is travel: 100 Open-Meteo sites, each visit leaving ~300 hours fetched days ahead
and never refreshed (e.g. Lviv/Kyiv/Warsaw sites with 300 of ~450 rows far-ahead). Expected saving
roughly **25 MB on the Pixel, ~2 MB on desktop** once the old rows age out (30-day retention); the
file shrinks only after a VACUUM (desktop already does it in housekeeping; Android reuses the pages).

## What the daily view reads from hourly today

| Use | Read | Days |
|---|---|---|
| Noon cloud (bar split + icon) | every paint, `DailyNoonCloudCover` (Android `DailyViewLogic:716`, desktop `DesktopDailyForecastModel:470`) | all future days |
| Day/night rain % shown | every paint, hourly max first, then row's stored value (`DailyRainLabels.resolveLiveDayNightChance`) | all future days |
| Day/night rain % stored on `forecasts` | at fetch, `withStoredPrecipPeriods`: provider value, else max over *stored* hourly | all fetched days |
| Rain-start summary | every paint | today … +2 (inside 72 h) |
| Past-day dashed fallbacks | every paint | past days (30-day retention, unaffected) |

Past days already use frozen values (`daily_history.noonCloudPercent`, day/night chances).

## Design

### 1. Three columns on `forecasts` (Room 78 / desktop 31) — no new table

`forecasts` is already the daily forecast table (high/low, condition, `daytime/nighttimePrecipProbability`),
and both platforms already load its rows for the daily view. Add:

| Column | Meaning |
|---|---|
| `noonCloudPercent` | what `DailyNoonCloudCover` resolves for that day (visible cloud: total, else the largest band) |
| `hourlyDayPrecipMax`, `hourlyNightPrecipMax` | `DailyRainLabels.periodMaxima` over 08–20 / 20–08 |

Why separate hourly maxima rather than reusing `daytime/nighttimePrecipProbability`: for NWS (and
Google) those hold the **provider's** period chance by an earlier decision (commit 3fa341b6), while
the display prefers the hourly max. NWS hourly reaches ~6.5 days, so dropping its days 4–6 hourly
would change NWS's shown rain % there. Two extra columns keep the display lossless; for sources
without provider values they equal the existing columns.

### 2. Computed from the full download, per field, only when covered; otherwise copied forward

`:shared` `DailyHourlySummaries`:
- `fromPayload(hourly, zone)` → per-day partial summaries. A field is present only when this payload
  **covers its whole window**: noon = a row at 12:00; day = rows from ≤ 08:00 to ≥ 20:00 − step;
  night = rows from ≤ 20:00 to ≥ 08:00 next day − step (step 1 h, or 3 h for OWM's 3-hourly series).
  A partial window (payload ends at 14:00, today's elapsed morning) yields nothing for that field.
- `carryForward(prior, incoming)` → per field: incoming if present, else the prior row's value.

`forecasts` gets a new set of rows per fetch (`fetchedAt` is in the key), so "never blank" is a rule
applied while writing: each new row takes its value from the download if covered, else from the
**newest earlier row for that day, source and site**. This is the same mechanism the write already
uses for the high/low cutoff (`ForecastSnapshotStore.saveForecastSnapshot`: `prior =
latestByDate[…]`, "keep the last real pre-cutoff prediction rather than nulling the field"); desktop
`DesktopWeatherDao.upsertForecasts` gets the same step. The "unchanged row" check that skips writing
must compare the new columns too.

Plumbing: the hourly save already precedes the daily save in one fetch on both platforms. The hourly
save computes the summaries from the whole payload **before** the 72 h trim and hands them to the
daily save (Android: `ForecastFetchCoordinator` → `saveDailyBatch`; desktop:
`persistForecastResult`). On-demand fetches (`HourlyOnDemandWorker`, desktop `extendHourlyFor`) save
a daily batch too, so they refresh the far days they reach. Climate-normal (GENERIC_GAP) rows are
filled the same way from GENERIC_GAP hourly.

### 3. Hourly stored to 72 h for every source

- `HourlyHorizons`: `routineHours = 72` for all sources (`maxHours` unchanged). Every source then
  has an on-demand range, so the existing `HourlyOnDemand` machinery serves days past 72 h.
- The save keeps rows up to `now + HourlyOnDemand.hoursAhead(source, request)`: 72 h routinely, the
  requested depth for an on-demand fetch. Applies to `hourly_forecasts` **and** the history snapshot
  (`hourly_forecast_history`) — the snapshot is where most bytes are. Elapsed-hour handling is
  unchanged. This is a trim before writing, not a new prune job.
- Rows already stored past 72 h are left alone and age out under the normal 30-day retention.

### 4. Readers: the forecast row first, then hourly, then what they fall back to today

- **Noon cloud** (future days): `forecasts.noonCloudPercent` → live hourly noon → 0 % (today's
  fallback). Past days keep `daily_history.noonCloudPercent`.
- **Displayed rain %**: `hourlyDay/NightPrecipMax` → live hourly window max → `daytime/nighttimePrecipProbability`.
- *Changed during implementation (2026-10-10):* the plan read hourly first. On the emulator the
  Open-Meteo hours past 72 h from the 07:48 fetch were still stored (the trim deletes nothing) while the
  11:03 fetch wrote fresher values on the row; hourly-first would have shown the older ones. The row is
  computed from the same download as any hourly it shares, so reading it first changes nothing inside
  72 h, and hourly remains the fallback for rows written before the columns existed.
- **Stored rain % on `forecasts`** (`DailyPrecipPeriods.resolve`, both platforms): provider value →
  the row's hourly max (this download, else stored hours, else carried). Without this, Open-Meteo's
  days 4+ would be written null — the blanking the user ruled out.
- One shared chain in `:shared` (`DailyHourlySummaries.liveDayNight`, `DailyRainLabels`) taking the
  forecast row's values as parameters; no new loader — the rows are already in hand on both platforms.

### 5. Retention

Unchanged: the columns live and die with `forecasts` (30 days).

### Logging

- `DAILY_SUMMARY source=… site=… days=N noon=n day=n night=n carried=n` (carried = fields copied
  from an earlier row because this payload did not cover them).
- `HOURLY_TRIM source=… kept=n dropped=n horizonH=72|<on-demand>`.

## Visible behaviour changes

1. **Hourly view past 72 h for free sources** (Open-Meteo, Silurian, NWS, Tomorrow.io, OWM) now
   fetches on tap or pan, under the existing "Fetching hourly forecast for {day}…" banner, as Google
   already does. One free request; previously instant from storage.
2. Hourly view of a **past** day at a site you left early shows no forecast line past 72 h after
   your last fetch there (it used to show the stale far-ahead forecast).
3. Daily view: none intended. Google days 4–10 still have no noon cloud unless that day was opened
   (same as today); using Google's daily `cloudCover` there is a possible follow-up, not in scope.

## Carry-forward age

A carried field can be as old as the last fetch that reached that day. Days move inside 72 h as they
approach, where every fetch refreshes them. No age cap proposed.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `fromPayload`: full 16-day hourly → noon/day/night for each full day | unit (:shared) |
| 2 | `fromPayload`: payload ends 14:00 → that day's noon present, day/night absent | unit |
| 3 | `fromPayload`: payload starts 10:00 (today) → today's day window absent, noon present | unit |
| 4 | `fromPayload`: OWM 3-hourly rows (09,12,15,18) cover the day window | unit |
| 5 | `fromPayload`: low-cloud null, total present → total used | unit |
| 6 | `fromPayload` noon equals `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent` on the same rows (equivalence over many days) | unit |
| 7 | `carryForward`: incoming absent field never overwrites the prior value | unit |
| 8 | `carryForward`: incoming value replaces the prior; no prior and absent → null | unit |
| 9 | `DailyPrecipPeriods.resolve`: provider → payload hourly max order | unit |
| 10 | Noon-cloud chain: hourly present ignores row value; hourly absent uses row; both absent → 0 | unit |
| 11 | Display rain chain: hourly → row hourly max → provider value (NWS day 5 keeps hourly-max display) | unit |
| 12 | `HourlyHorizons`: routineHours 72 for every source, maxHours unchanged | unit |
| 13 | `hoursToCover`: Open-Meteo day 6, stored to 72 h → fetch, hours ≥ day end | unit |
| 14 | Android: 16-day Open-Meteo fetch → live and history hourly ≤ now+72 h; forecast rows for 16 days carry noon/day/night | integration (Robolectric, coordinator + stores + DAO) |
| 15 | Android: 16-day fetch then Google-style 24 h hourly fetch → newest rows for days 2–16 keep earlier values | integration |
| 16 | Android: second full fetch with a new noon cloud → newest row has the new value | integration |
| 17 | Android: unchanged high/low but changed noon cloud → a new row is written (unchanged-check includes new columns) | integration |
| 18 | Android: on-demand 168 h fetch → hourly kept to 168 h, days 4–7 rows refreshed | integration |
| 19 | Android: daily render with hourly to 72 h + row values → same noon cloud, icons, rain labels as with full hourly (golden comparison) | integration (DailyViewLogic) |
| 20 | Desktop: 14–19 equivalents through `DesktopWeatherRepository` + `DesktopWeatherDao` | integration |
| 21 | Room migration 77→78 adds columns; schema export; desktop 30→31 | migration test |
| 22 | GENERIC_GAP rows filled from GENERIC_GAP hourly; climate-normal day reads them | integration |

## On-device verification

1. Install on Pixel; force a refresh. `DAILY_SUMMARY` / `HOURLY_TRIM` logs per source; no
   `hourly_forecasts` row past now+72 h written after install.
2. Screenshot the daily view before and after for Open-Meteo and Silurian (8-column widget):
   icons, bar splits and rain % identical.
3. Tap day 6 on Open-Meteo: banner, then hourly graph.
4. Desktop: same via `scripts/buildStart-desktop.sh`; popup daily view unchanged.
5. A week later: re-measure table sizes.

## Verification

- Unit/integration: `:shared` + `:desktop` suites green; `:app` 2460 unit tests green. New:
  `DailyHourlySummariesTest` (14, incl. 200-day noon equivalence with `DailyNoonCloudCover`),
  `DailyRainLabelsTest` (+4), `DailyPrecipPeriodsTest` (rewritten), `HourlyOnDemandTest` (+3),
  `DesktopDailySummaryStorageTest` (4, real SQLite), `DailySummaryStorageIntegrationTest` (5,
  Robolectric + Room: coordinator + stores), `DesktopNoHourlyDayClickTest` (+1).
- Room migration 77→78: `WeatherDatabaseMigrationTest` 24/24 on the emulator.
- Emulator, 11:03 forced refresh after install: DB v78; Open-Meteo's newest batch has noon/day/night
  on all 16 days (last night empty — the download ends mid-night); every hourly row written by the
  fetch, live and snapshot, ends at +70.9 h; Google days 4–10 have no values (unchanged: Google was
  already 72 h).
- Found on device and fixed: rows past 72 h from the 07:48 fetch were still stored, so hourly-first
  would have shown older values than the row → readers now take the row first (section 4).
- Also changed during implementation: a free source's on-demand fetch keeps its whole horizon, and a
  fresh one means "nothing more to fetch" (`HourlyOnDemand.hoursAhead` / `hoursToCover`); otherwise
  NWS (data ends ~150 h) would refetch on every tap past its data.
- Pixel, 11:12 forced refresh: DB v78; newest batches carry the values on Open-Meteo 16/16 days,
  Silurian 15/15, NWS 7/8, Google 7/10 (last nights empty: downloads end mid-night); every hourly row
  written, live and snapshot, ends at +70.8 h; widget draws normally.
- Not yet verified live: a desktop fetch (startup respects the forecast cadence; DB is at v31), the space actually reclaimed (old rows age
  out over 30 days). Not done: test 22 (GENERIC_GAP rows) — no code writes GENERIC_GAP hourly today,
  and climate-normal days keep reading hourly as before.
