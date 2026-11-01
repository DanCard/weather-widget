# One hourly table: phases 2 and 3 recap

Plan: `plans/261101-one-hourly-forecast-table.md`. Phase 1: `summaries/261101-one-hourly-table-phase-1-recap.md`.
Commits: `02f3bc81` (phase 2), `609d02f7` (phase 3).

## Outcome
`hourly_forecast_history` is merged into one append-only `hourly_forecasts` (Room v84, desktop schema v36) on the
desktop, Pixel, Samsung and emulator. A fetch only adds rows, so no fetch can change what an earlier fetch
predicted, for any source. The upgrade is one-way on each device: going back means restoring a backup.

## Phase 2
Removed the 72 h snapshot limit: a fetch saves every hour it keeps. On the emulator, snapshots went from about
72 hours to 192 (Open-Meteo, Silurian), 155 (NWS), 118 (OpenWeatherMap) and 98 (Tomorrow.io).

## Phase 3 changes
1. Shared `HourlyTableMerge` SQL used by Room `MIGRATION_83_84` and desktop v36.
2. `HourlyForecastEntity` gained the label; `HourlyForecastHistoryEntity` stays as a keyless shape until phase 4.
3. Every fetch appends under its fetch time. `HourlySnapshotRun` and its Hosts are gone, earlier than planned, because
   its 5-minute label reuse would have let a second fetch replace the first fetch's rows.
4. Newest-per-hour queries on both platforms, using `GROUP BY ... HAVING fetchedAt = MAX(fetchedAt)`.

## Bugs caught on the way
- Room's `@Delete` on a keyless class matched every column, and `= NULL` matches nothing, so no row with an empty
  field could be pruned. Fixed with `HourlyRowKey`. Found by `HistorySnapshotPrunerTest`.
- `OPEN_METEO_PRIOR24` leaked into all-sources reads. Excluded on both platforms. Found in emulator data.
- The 30-day live delete would have removed settled history. Removed.
- Desktop's startup DDL would have recreated an empty history table on every launch. Fixed.

## Verification
- Real database copies before any device: integrity ok, exact row counts (desktop 52,360, Samsung 38,396,
  Pixel 69,846), merge under 50 ms.
- About 2,000 older rows per database read newer than the old live row. All predate 2026-10-31 under window labels,
  with identical temperature and rain chance.
- JVM gate: 6,498 tests passed. Emulator: 114 instrumented tests passed, including `migrate83To84`.
- Samsung after upgrade: v84, four widgets painting, 39,152 rows (38,396 merged plus 756 new from five sources;
  `PRIOR24`'s 754 replaced their own labels).
- A first Samsung integrity check reported malformed pages. That was a torn copy taken during a write: three quiet
  copies passed `integrity_check`, and the device logged no corruption.

## Deviations and costs
- `installDebug` reached the Pixel at the same moment as the emulator, not after it, because it installs on every
  connected device.
- Two forced full syncs (Samsung, emulator) used API quota.

## Not verified
- Before-and-after screenshots of the daily, temperature, rain and cloud views.
- Before-and-after daily freeze, rain accuracy and prior-day values on database copies.
- Widget speed.
- The observations table on a device.
- The first real prune on the merged table.

## Remaining
Phase 4: remove `merge`, `ElapsedForecastBackfill` and `HourlyForecastHistoryEntity`. Phase 5: living docs.

Backups: `backups/20261101_093306_*`, `~/weather-widget-prephase3`.
