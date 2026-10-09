# Data pruning and retention

Last verified 2026-10-09. Android `:app`, desktop `:desktop` and `:shared` share the rules below; each
platform runs them in its own place.

## The short answer

Two things delete stored weather data. They have different goals and run at different times:

| | Goal | What it deletes | Changes what is drawn? | When |
|---|---|---|---|---|
| **Retention** | Bound how far back the DB reaches | Rows **older** than a per-table age | Only data past the age limit; screens stop at those limits anyway (history browsing 30 days) | Every sync / refresh |
| **Snapshot prune** | Space | **Redundant copies** of the same forecast hour in `hourly_forecast_history` | **No**, by construction | Once a day, **only while charging with the screen off** |

Nothing else prunes. The on-demand-hours prune added on 2026-10-09 was removed the same day (see
[Removed](#removed-on-demand-hours-prune)).

## Retention (age-based)

One policy for both platforms: `:shared` `RetentionPolicy` (user's decision, 2026-09-30).

| Table | Kept |
|---|---|
| `daily_history` | 18 months (`DAILY_HISTORY_DAYS`) — the long record: accuracy stats, history |
| `api_usage_stats`, desktop `network_usage` | 90 days (`USAGE_DAYS`) — Usage stats' 90-day and month columns |
| `forecasts`, `hourly_forecasts`, `hourly_forecast_history`, `current_status`, `station_cache` | 30 days (`DEFAULT_DAYS`) |
| `observations` | 10 days (`OBSERVATION_DAYS`) |
| `app_logs` | 72 h (`APP_LOG_HOURS`) plus row caps; desktop keeps its permanent `*_BACKFILL_DONE` markers |
| `climate_normals` | current location only |

- **Android:** `WeatherRetentionManager.cleanOldData()`, at the end of each successful
  `getWeatherData` fetch.
- **Desktop:** `DesktopWeatherDao.applyRetention()` inside each `refresh()`.

These are plain indexed `DELETE … WHERE <time> < cutoff` statements, cheap enough to run inside a sync,
so they are not gated on power or screen.

## Snapshot prune (`hourly_forecast_history`)

### Why it exists

Every forecast fetch writes a full snapshot of every hour it returns into `hourly_forecast_history`,
keyed by a 4-hour bucket (`timestampToGroupPredictions`). At a site the phone stays at, each hour
accumulated about **30 copies per source**. On the Pixel 7 Pro (2026-09-29) that table plus its two
indexes was **50 MB of a 95 MB database**. Retention alone cannot help: the copies are all younger than
30 days. See `performance/260929-hourly-history-snapshot-retention.md`.

### What is kept, what is pruned

The spec is `:shared` `HistorySnapshotRetention`. For each (source, site, hour) it keeps the union of
what the readers use, and deletes the rest:

1. **The newest copy**, by bucket and by `fetchedAt`: the hourly graphs' stitcher
   (`HourlyForecastLoader`, `GraphDataLoader`, desktop `getHourlyWithHistory`).
2. **The newest copy with a value for each nullable field** (cloud cover per band, precip %, precip
   amount): the stitcher fills a null field on the newest copy from older ones (NWS near-term sky
   cover).
3. **The newest copy made ≥ 24 h before the hour that carries a mid or high cloud band**: the cloud
   graph's "yesterday's forecast" band (`PriorDayBandForecast`).
4. **The newest copy captured on the hour's previous local day**: rain accuracy's "1-day-ahead"
   forecast (`RainAccuracyCalculator`).

Measured on the Pixel DB: rules 1–3 kept 149k of 248k rows (−40 %); long-stay sites shrink about 10×.

### Why it changes nothing on screen

Each rule keeps "the newest row within a window that never moves". New snapshots only ever arrive with
later buckets, so a row that is not the newest in its window now can never become the newest later.
Pruning is therefore safe for future hours as well as past ones, and running it twice changes nothing.
Every reader gets the same result before and after; the SQL prune on both platforms is tested against
the shared spec.

**Rule for new code:** any new reader of older snapshots must add its window to
`HistorySnapshotRetention` and its equivalence tests, or the prune will delete what it reads.

One-shot repairs that read *every* snapshot (`FrozenRainChanceRepair`, the frozen-display and chance
backfills) must have finished first; both platforms skip the prune until their done-flags are set.

### When it runs

At most once per 24 h, and **only while charging with the screen off** (user, 2026-10-09: pruning is
housekeeping, never worth battery or a busy device):

- **Android:** after a fetch, `ForecastRepository.pruneHistorySnapshotsIfDue()` *enqueues*
  `HistoryPruneWorker` (unique, KEEP) — it never runs inside the fetch. Its first pass took 96 s on
  the Pixel and, back when it ran under `syncMutex`, stalled a forced sync to 113 s. The job's
  constraints are **charging + device idle**: the screen off and the phone unused for a while. On a
  phone charged overnight it runs then.
- **Desktop:** `DesktopWeatherRepository.pruneHistorySnapshotsIfDue()` inside the daemon's refresh.
  When it is due but `housekeepingAllowed()` is false, it returns without a log row, so the next
  refresh asks again. That check is: on AC (`PowerDetector`; no battery counts as AC) and screen off
  (`ScreenStateDetector`, via `xset` DPMS or `loginctl` LockedHint). An undetectable screen counts
  as on, so the prune waits. A pass takes about 0.1 s here and is followed by `VACUUM` when the free
  list is large enough.

Log tag on both platforms: `HISTORY_PRUNE` (`days= scanned= deleted= kept= ms=`, or
`skipped=…_pending`).

## Removed: on-demand-hours prune

Added and removed on 2026-10-09 (`plans/261009-google-hourly-on-demand-past-72h.md`). Google keeps
72 h of hourly routinely. A tapped day past that is fetched on demand (`HourlyOnDemand`), and routine
fetches never refresh those extra hours. The prune deleted them once they were 12 h old.

It was removed for two reasons:

- **It caused visible inconsistency.** Those rows are not only the tapped day's hourly graph: the
  daily view reads them for each future day's **noon cloud shading** (`DailyNoonCloudCover`, at
  render time). After a tap, days past 72 h gained cloud shading; 12–36 h later the prune deleted the
  rows and the shading disappeared. The same day looked different depending on when you last tapped.
- **It was not needed.** A tap already ignores on-demand rows older than 12 h
  (`HourlyOnDemand.MAX_EXTENSION_AGE_MS`) and refetches, so stale rows are never trusted for the
  tapped day. The space is a few hundred rows, and the 30-day retention removes them.

**Lesson:** before deleting rows to save space, list everything that reads them — including the daily
view's render-time reads of hourly data (day/night rain %, noon cloud), not only the screen that
fetched them.

## Key files

| Concern | File |
|---|---|
| Retention ages | `shared/.../data/local/RetentionPolicy.kt` |
| Snapshot keep rule (spec) | `shared/.../data/local/HistorySnapshotRetention.kt` |
| Android retention + prune trigger | `app/.../data/repository/WeatherRetentionManager.kt`, `ForecastRepository.kt` |
| Android prune job + constraints | `app/.../data/repository/HistoryPruneWorker.kt`, `HistorySnapshotPruner` |
| Desktop retention + prune | `shared/.../data/local/desktop/DesktopWeatherDao.kt` (`applyRetention`, `pruneHourlyHistorySnapshots`), `desktop/.../DesktopWeatherRepository.kt` (`pruneHistorySnapshotsIfDue`, `housekeepingAllowed`) |
| Tests | `HistoryPruneWorkerTest` (constraints), `DesktopHousekeepingGateTest` (gate), snapshot-retention equivalence tests |
