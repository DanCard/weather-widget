# hourly_forecast_history: keep the snapshots something reads, not every fetch

Status: **investigation done, plan.** Companion to
`performance/260929-hourly-history-newest-snapshot-query.md` (read less) — this one stores less.

## Finding

Every fetch writes a full snapshot of every hour it returns (`HourlyForecastStore` → `insertAll`),
kept 30 days (`WeatherRetentionManager.deleteOldHistory`). On the Pixel 7 Pro DB (2026-09-29):

- `hourly_forecast_history` + its two indexes = **50.4 MB of a 94.7 MB database** (table 21.7 MB,
  PK autoindex 15.2 MB, location index 13.5 MB).
- At a site the phone stayed at, each hour holds ~30 snapshots per source (Kyiv: Open-Meteo 28,
  Silurian 30); short visits hold 1–2.

## Who reads the depth, and what they need

| Reader | Needs, per (source, site, hour) |
|---|---|
| `HourlyForecastLoader`, `GraphDataLoader`, desktop `getHourlyWithHistory` (stitcher) | newest; older only to fill a null field on the newest (NWS near-term skyCover) |
| `PriorDayBandForecast` / `getPriorDayBandForecast` (+ desktop) | newest made 24–48 h before the hour |
| `RainAccuracyCalculator` | newest captured during the previous calendar day ("1-day-ahead") |
| `HourlyForecastStore` elapsed-hour coverage check | any row |
| `FrozenRainChanceRepair`, frozen-display backfill (`DailyHistorySnapshotter`) | all snapshots, but one-shot migrations gated by prefs flags |
| `getPriorDayCloudForecast` | reads `OPEN_METEO_PRIOR24` rows (a separate source, 1 per hour) |

## Keep rule

Per (source, site, hour), keep the union of:
1. the newest snapshot;
2. the newest snapshot with `bucket <= hour - 24 h` (prior-day band, also a clean 1-day-ahead);
3. the newest snapshot captured on the hour's previous calendar day (rain accuracy);
4. any older snapshot that is the newest non-null source of a coalesced field the kept rows lack
   (stitcher equivalence; NWS skyCover).

Measured on the same DB: rules 1–3 keep **149,116 of 248,013 rows (−40 %)**; newest-only would be
144,380. The saving is modest DB-wide because most sites are short visits, and ~10× at long-stay
sites. With `plans/260929-follow-device-weather-site-radius.md` merging nearby visits into one
site, more hours become long stacks and the saving grows.

## Plan

1. **Pure rule in `:shared`** (`HistorySnapshotRetention.keep(rows): Set<key>`), tested against each
   reader's own selection: for generated snapshot stacks, every reader's output is identical on the
   full vs the pruned set (stitcher, `PriorDayBandForecast.select`, the rain-accuracy "latest
   captured on the prior day" reducer).
2. **Prune job:** extend `WeatherRetentionManager` (Android) and the desktop retention path to delete
   non-kept rows for **past hours only** at first; future hours still receive new snapshots, so their
   keep set is not final. Batch by site and day to stay off the main paint path. Log
   `HISTORY_PRUNE deleted=… kept=… ms=…`.
3. **Guard the one-shot repairs:** confirm `PREF_CHANCE_REPAIR_DONE` and
   `PREF_FROZEN_DISPLAY_BACKFILL_DONE` are set before pruning on an existing install. If not, run
   them first or skip pruning that install until they have.
4. **Reclaim space:** SQLite does not shrink on DELETE. Measure the freelist after the first prune;
   `VACUUM` once, on charger, if it is worth tens of MB.
5. **Device check:** pull the DB before and after; compare row counts and file size, and diff the
   rendered `HOURLY_LOAD`/`DAILY_RENDER` lines, the Statistics rain accuracy, and the prior-day band
   for the same days.

## Order relative to the other two plans

1. Weather-site radius (stops new fragments, cheapest).
2. This retention (storage −40 % now, more once sites merge; render reads shrink with it).
3. The newest-snapshot *read* plan: re-measure after 1–2. At ≤3–4 rows per hour it may no longer be
   worth its complexity.

## Implementation and results (2026-09-29/30)

**Rule** (`:shared` `HistorySnapshotRetention`, the spec): per (source, exact site, hour) keep the
newest by bucket and by fetchedAt, the newest non-null row per coalesced field, the newest
band-carrying row ≥24 h before the hour, and the newest captured on the hour's previous local day.
Every rule is "newest within a window that never moves", so **future hours are safe to prune too**
(the plan's "past hours only" caution was unnecessary), and the prune is idempotent.

**Proof:**
- Property tests run the real readers on generated stacks (300 seeds; multi-site, ties, nulls, live
  overlap): `HourlyForecastStitcher.stitchBySource` and `PriorDayBandForecast.select` in `:shared`,
  `RainAccuracyCalculator.latestSnapshotPrecipByHour` in `:app`. Each rule was mutation-checked:
  dropping it makes its reader diverge.
- The Android (Room) and desktop (JDBC) prunes leave exactly the spec's keep set on real databases.
- Desktop real-data check: the three desktop readers (`getHourlyHistory`, `getHourlyWithHistory`,
  `getPriorDayBandForecast`) on a copy of the live DB, original vs pruned, over 31 site/source pairs
  × 161 week windows: **0 differences**.

**Why not SQL:** the rain rule is about local calendar days; SQLite 'localtime' follows the process
TZ and a fixed offset breaks across DST. The prune runs the Kotlin spec one local day at a time.

**Desktop** (live DB, first prune after wake 2026-09-30 05:25): 535,490 → 34,579 rows (−94 %) in
3.3 s; `VACUUM` freed 91.6 MB in 0.43 s: **183 MB → 82.6 MB**. Runs from the refresh, daily,
gated on the `CHANCE_BACKFILL_DONE` / `FROZEN_DISPLAY_BACKFILL_DONE` markers; VACUUM only when
≥16 MB is free.

**Android** (Pixel, first prune 2026-09-30 00:07): 249,873 → 149,053 rows (−40 %, as estimated);
free list 0 afterwards and the file shrank 95.9 → 81.4 MB with no VACUUM.
**But it ran for 96 s inside `getWeatherData` under `syncMutex`:** that forced sync took 113 s
(`weather=106318ms`), its paint came ~96 s late, and every other sync waited on the lock.
Fixed the same day: retention only **enqueues** `HistoryPruneWorker` (unique, KEEP,
battery-not-low), and the worker visits only days holding hours that received snapshots since its
last pass (`touchedSinceFetchedAt`). The first pass is the only full scan.
