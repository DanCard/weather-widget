# What the first hourly-history prune deleted (2026-09-29/30)

Plan: `performance/260929-hourly-history-snapshot-retention.md`. Rule: `:shared`
`HistorySnapshotRetention`.

## Scope

- **Only table touched:** `hourly_forecast_history`, the per-fetch snapshots of each hour's forecast.
- **Not touched:** `forecasts` (daily), `hourly_forecasts` (live hourly), `observations`,
  `daily_history`, `climate_normals`, `app_logs`, or any preference.
- **What went:** older, superseded copies of an hour's forecast that no reader selects. Every
  (source, site, hour) that had rows still has at least one: the newest.

## Kept, per (source, exact site, hour)

1. The newest snapshot, by capture bucket and by `fetchedAt` (hourly graph / stitcher).
2. For each nullable field (`cloudCover`, `cloudCoverLow/Mid/High`, `precipProbability`,
   `precipAmountMm`), the newest snapshot where it is non-null (the stitcher fills a missing field
   from an older copy).
3. The newest snapshot carrying a mid/high cloud band captured ≥24 h before the hour (frozen
   prior-day band).
4. The newest snapshot captured on the hour's previous local calendar day (rain accuracy,
   "1-day-ahead").

## Desktop (this machine, live DB)

First prune 2026-09-30 05:25:25, then `VACUUM` at 05:25:26.

| | Before | After |
|---|---|---|
| `hourly_forecast_history` rows | 535,490 | 34,579 (**500,911 deleted, −94 %**) |
| Snapshots per (source, site, hour) | avg 21.5, max 113 | avg 1.4, max 3 |
| `weather.db` size | 183 MB | 82.6 MB (VACUUM freed 91.6 MB) |

By source (rows before → after):

| Source | Before | After |
|---|---|---|
| OPEN_METEO | 232,512 | 9,493 |
| SILURIAN | 176,181 | 8,059 |
| NWS | 85,895 | 5,831 |
| TOMORROW_IO | 25,748 | 3,276 |
| OPEN_WEATHER_MAP | 7,131 | 1,693 |
| OPEN_METEO_PRIOR24 | 6,133 | 6,140 (one row per hour already; all kept) |
| WEATHER_API | 1,152 | 240 |
| Generic | 3 | 3 |

By site (rounded to 0.01°):

| Site | Before | After |
|---|---|---|
| Mountain View 37.42,-122.09 | 471,847 | 20,707 |
| Lviv 49.84,24.03 | 31,168 | 5,983 |
| Kyiv 50.45,30.52 | 23,173 | 3,124 |
| Warsaw 52.23,21.07 | 5,108 | 2,068 |

Before/after counts come from the pre-prune copy vs the live DB a few minutes after the prune, so
"after" includes the rows fetched in between (the prune itself kept 34,579).

## Android (Pixel 7 Pro)

First prune 2026-09-30 00:07:48 (`days=78 scanned=249873 deleted=100820 kept=149053 ms=96142`).
No VACUUM was needed: the free list was empty afterwards.

| | Before | After |
|---|---|---|
| `hourly_forecast_history` rows | 249,873 | 149,053 (**100,820 deleted, −40 %**) |
| Snapshots per (source, site, hour) | avg 1.7, max 40 | avg 1.0, max 4 |
| `weather_database` size | 95.9 MB | 81.4 MB |

The phone saves less than the desktop because most of its sites were short visits (1–2 snapshots per
hour); the deep stacks were at the long stays.

By source (rows before → after; "after" is 05:31 and includes ~1,800 rows fetched since):

| Source | Before | After |
|---|---|---|
| OPEN_METEO | 88,836 | 44,405 |
| SILURIAN | 70,734 | 36,337 |
| OPEN_METEO_PRIOR24 | 36,498 | 36,480 |
| TOMORROW_IO | 15,973 | 12,320 |
| OPEN_WEATHER_MAP | 15,514 | 12,242 |
| NWS | 13,759 | 1,745 |
| WEATHER_API | 8,513 | 7,362 |

By area (rounded to 0.1°):

| Area | Before | After |
|---|---|---|
| Lviv 49.8,24.0 | 87,076 | 53,607 |
| Mountain View 37.4,-122.1 | 44,821 | 7,392 |
| Kyiv 50.5,30.5 | 28,395 | 11,281 |
| Kyiv 50.4,30.5 | 20,990 | 12,225 |
| Warsaw 52.2,21.0 | 5,919 | 5,626 |

## Why this is safe

- Property tests run the real readers on full vs pruned data and get identical output:
  - `HourlyForecastStitcher.stitchBySource` and `PriorDayBandForecast.select` in `:shared`;
  - `RainAccuracyCalculator.latestSnapshotPrecipByHour` in `:app`.
  Dropping any one keep rule makes its reader diverge (mutation-checked).
- On a copy of the desktop DB, the desktop readers (`getHourlyHistory`, `getHourlyWithHistory`,
  `getPriorDayBandForecast`) over 31 site/source pairs × 161 week windows gave **0 differences**
  between original and pruned.
- The one-shot repairs that read every snapshot (chance backfill, frozen-display backfill, rain-chance
  site repair) had all run before the prune. Both platforms gate on them.

## Recovery copies (pre-prune, gitignored)

- Desktop: `backups/20260929_231900_desktop_pre_history_prune/weather.db` (534,755 history rows,
  taken minutes before the prune).
- Phone: `backups/20260929_231816_pixel_7_pro_2A191FDH300PPW/databases/weather_database`
  (249,827 history rows, 23:18, before the 00:07 prune).

## Incident during the phone's first prune

The prune originally ran inside `getWeatherData` under `syncMutex`. The first pass took 96 s, so
that forced sync took 113 s, its widget paint came ~96 s late, and other syncs waited on the lock.
Now fixed: the fetch only enqueues `HistoryPruneWorker`, and later passes visit only days with new
snapshots.
