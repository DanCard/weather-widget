# Desktop: latest-only observation loop (drop the 7-day history re-fetch) — 2026-08-20

## Goal

Cut the desktop daemon's **10-minute observation loop** from ~2 500 rows/cycle to ~5 rows/cycle,
removing the single biggest source of redundant network + parse + DB work behind the frequent
`weather-widget-` CPU bursts in `~/misc/logs/sys-logging-*.log`.

## Evidence

The user reported `weather-widget-` at the top of `sys-logging-2026-08-20.log` 3 samples in a row,
with the **window process not running** — i.e. the headless daemon (PID 1229789) alone.

Correlating the `top` samples against the daemon's autostart log
(`~/.local/state/weather-widget/autostart-20260819-203647.log`):

| `top` sample | `weather-widget-` | Daemon log activity in that window |
|---|---|---|
| 10:00:51 | 4.1 % | tail of the 10:01:11 forecast burst |
| 10:01:20 | 9.3 % | 10:01:11 `Loop forecast refresh` (NWS: gridpoint + 5 stations × 500-row history) + 10:01:12 `Non-primary actuals` (3 sources) |
| 10:01:50 | 5.4 % | 10:01:47 `Temp actuals loop refresh` — **the same 5 stations × 500-row history again** |

That last line is the smoking gun. Every **10 minutes** the `Temp actuals` loop runs
`refreshObservations()` → `fetchObservationsOnly()` → `fetchNwsObservationsOnly()` →
`fetchObservationBundles(stations)`, which for each of up to 5 stations fetches:

1. `nwsApi.getObservations(start, end)` — a **7-day** window (`HISTORY_DAYS = 7`), ~500 rows/station
   (`count=500`, `count=477`, `count=478` …) ≈ **2 500 rows per cycle**; then
2. `nwsApi.getLatestObservationDetailedResult(stationId)` — the one *latest* reading (~10 rows);
3. Synoptic web fallback for the nearest 3 stations (already cheap/bounded).

The loop only needs **#2 and #3** for its actual job (the current-temperature IDW blend uses the
latest reading per station). #1 is the redundant 7-day history — re-fetched and re-upserted
identically every 10 min.

## Why history is safe to drop from this loop

The 7-day station history is **also** fetched (and written to the same `observations` table) by
three other paths that the 10-min loop doesn't touch:

| Path | Trigger | Fetches 7-day history? |
|---|---|---|
| `refresh()` → `fetchNwsForecast()` → `fetchObservationBundles(stations)` | launch `FULL_FORECAST`, hourly forecast loop, resume/network kick, location/source change | **yes** |
| `ensureHistory()` → `fetchObservationHistory()` | user pans the hourly graph past cached depth | yes (on demand) |
| `fillNwsStationActualsIfNeeded()` | daily extremes with missing api actuals | per-day complete pulls |

Both `refresh()` and `refreshObservations()` write into the same `observations` table via
`upsertObservations` (`INSERT OR REPLACE`, PK `(stationId, timestamp)`). The forecast loop fetches
the *same* 7-day window for the *same* stations, so the observation loop's history fetch is 100 %
redundant — the only new rows it contributes are the latest reading(s) that arrived since the last
cycle, which is exactly what a latest-only fetch captures.

`loadCached()` (the pink "actual" line) and `recomputeDailyExtremes()` both read history **from the
DB**, not from this fetch's return value, so they keep working off whatever the forecast loop
persisted.

## Answer: how does it work if history is missing?

This is the key question. There are two distinct cases — "missing from the DB" vs "stale":

1. **Missing from the DB (fresh install, DB wipe, new location).** The first thing the daemon does
   on launch is `runLaunchRefresh` → `determineLaunchRefreshAction`. With `cachePresent = false`
   (no cached hourly/daily rows) the action is **`FULL_FORECAST`**, which fetches the full 7-day
   history before the 10-min loop ever matters. A location/source change re-runs `startFetchLoops()`
   → the same `runLaunchRefresh`. So history is seeded on the paths that need it, independent of the
   10-min loop.

2. **Stale (forecast endpoint down for hours, observation endpoint up).** The hourly forecast loop
   keeps retrying `refresh()` every cycle regardless of the observation loop, so history is
   re-seeded the moment the forecast endpoint recovers. During the outage the pink actual line and
   daily extremes simply hold their last-known values — the same graceful degradation they have
   today, and the latest-only loop still keeps *current temp* fresh because it doesn't depend on
   history at all.

**Residual risk (accepted, documented):** the current 10-min loop doubles as a redundant backfill
when `/forecast` is down but `/stations/{id}/observations` is up. With latest-only, history stops
being backfilled during that specific split outage. Mitigants: (a) both paths share the same
`getGridPoint`/`getObservationStations` preconditions, so the failure modes are strongly correlated;
(b) the hourly forecast loop self-heals within ≤60 min; (c) history goes *stale*, never *wrong* —
the DB keeps serving last-known actuals. This is a bounded, observable trade, not a correctness
regression.

Optionally (only if we later see a real split outage in practice), add a cheap guard in
`refreshObservations()`: if the DB has no observation rows older than 24 h for this location/source,
fall back to a full-history fetch for that one cycle. This is deliberately **not** in the first cut
to keep it simple.

## Design

### 1. `DesktopWeatherService` — add a latest-only bundle path

Add a `latestOnly: Boolean = false` parameter to `fetchObservationBundles`, or a sibling
`fetchLatestObservationBundles(stations)`. When latest-only, per station:

- **Skip** the `nwsApi.getObservations(start, end)` 7-day call entirely.
- Always call `getLatestObservationDetailedResult(stationId)` (drop the current
  `if (historical.isNotEmpty())` gate — latest must no longer depend on history).
- Keep the existing Synoptic web fetch-first / prefer-newest merge unchanged (it only consumes the
  latest reading + `newestObservationMs`).
- Build the bundle with `historical = emptyList()`, `historicalIsWeb = false`, and
  `latest`/`latestIsWeb` from the prefer-newest merge.

`fetchNwsObservationsOnly()` then produces `rawObservations = latest readings + NWS_BLEND`
(no historical series) when latest-only, so `providerCurrentTemp`/`providerCurrentObservedAt`
are unchanged.

### 2. `WeatherApiClient` — surface the flag

`fetchObservationsOnly(latestOnly: Boolean = false)`. The repository passes
`latestOnly = true` for the periodic 10-min loop. Default stays `false` so the interface change is
non-breaking for other callers/tests.

### 3. `DesktopWeatherRepository.refreshObservations()` — pass the flag

The repository's `refreshObservations()` is called from exactly the "current temp" paths
(10-min `Temp actuals` loop + launch `OBSERVATIONS` action). Pass `latestOnly = true`.

Keep everything after the fetch (`upsertObservations`, `recomputeDailyExtremes`,
`snapshotDisplayedRainChance`, backfills, `loadCached`) unchanged — they already read history from
the DB.

### 4. `DaemonProcess` — no change

The loop cadence (10 min AC via `DesktopFetchStrategy.AC_OBSERVATION_MINUTES`) is out of scope for
this plan. Only the *payload* of each cycle shrinks.

## Files to change

| File | Change |
|---|---|
| `desktop/.../DesktopWeatherService.kt` | latest-only branch in the observation-bundle path; `fetchNwsObservationsOnly(latestOnly)`; `fetchObservationsOnly(latestOnly)` |
| `desktop/.../WeatherApiClient.kt` | `fetchObservationsOnly(latestOnly: Boolean = false)` |
| `desktop/.../DesktopWeatherRepository.kt` | pass `latestOnly = true` in `refreshObservations()` |

## Tests

- **Update `DesktopSynopticFallbackTest`** (`fetchObservationsOnly falls back to Synoptic …`): it
  stubs `getObservations`; with latest-only that stub becomes unused. Adjust to latest-only
  expectations — assert `getObservations` is **not** called while latest + Synoptic prefer-newest
  still produce the fresh value.
- **New test**: latest-only `fetchObservationsOnly()` returns `rawObservations` = latest + NWS_BLEND
  and issues **zero** `getObservations` calls (verify with mockk), while `fetchObservationsOnly(latestOnly = false)` keeps the history call.
- **New repository test** (mirrors `DesktopRefreshObservationsTest`): latest-only refresh still
  returns the DB-derived observation set (panel/popup parity invariant) and still recomputes
  extremes from pre-seeded history.
- All `@Category` buckets respected (`:desktop:testShortDesktop` / `testByDurationDesktop`).

## Verification

1. `./gradlew :desktop:test` (or `:desktop:testByDurationDesktop`).
2. Run the app, then in the daemon log confirm the 10-min `Temp actuals` cycle no longer emits
   `historical observations: station=… count=500` lines — only `getLatestObservationDetailedResult`
   + Synoptic lines.
3. Watch `~/misc/logs/sys-logging-*.log`: the recurring ~4–9 % `weather-widget-` blips should drop
   to ~1 % (network + parse only), and the 3-in-a-row hourly stacking should shrink to the single
   hourly forecast burst.
4. Confirm current temp + pink actual line still render (history served from the hourly forecast
   fetch).

## Out of scope (follow-ups, not in this plan)

- De-colliding the hourly forecast / 30-min non-primary / 10-min observation loops (separate plan).
- Relaxing `AC_OBSERVATION_MINUTES` 10 → 15 min.
