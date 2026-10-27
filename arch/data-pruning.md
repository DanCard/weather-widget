# Data retention and snapshot pruning

Android and desktop share retention rules from `:shared`, but execute them through their own storage
layers. Two mechanisms are involved:

- **Age-based retention** bounds how long each table remains available.
- **Hourly snapshot pruning** removes forecast variants that no reader can select, while retaining
  the active comparison windows and a smaller settled archive.

## Age-based retention

The authoritative durations are in
`shared/src/main/kotlin/com/weatherwidget/data/local/RetentionPolicy.kt`.

| Data | Retention |
|---|---|
| `daily_history` | 18 months |
| `hourly_forecast_history` | 30-day active window, then one settled row per source/site/hour for 18 months |
| `forecasts`, `hourly_forecasts`, `current_status`, `station_cache` | 30 days |
| `observations` | 10 days |
| `api_usage_stats`, desktop `network_usage` | 90 days |
| `source_view_days` | 30 days |
| `app_logs` | 72 hours plus row caps; permanent desktop backfill markers are exempt |
| `climate_normals` | Current location only |

Android runs retention through `WeatherRetentionManager`; desktop runs it through
`DesktopWeatherDao.applyRetention()`. Saved configuration and API keys are preferences/configuration,
not weather rows, and are not erased by this schedule.

## Which hourly snapshots are written

`HourlySnapshotRun` labels a snapshot with the fetch's actual time, not the start of a four- or
twelve-hour bucket.

- The displayed source is snapshotted on every fetch.
- Another recently viewed source is snapshotted when its previous snapshot is at least 11 hours old
  on a charger/high battery, or 22 hours old below that threshold.
- A source outside the recent-view background set is not snapshotted.
- Live-hourly writes and elapsed-hour backfill from the same fetch reuse one label.

`ForecastPersistRun` coordinates the live-hourly, snapshot, elapsed-backfill, and daily writes so both
platforms use the same order.

## Active-window prune rule

`HistorySnapshotRetention` is the specification; Android and desktop SQL pruning are tested against
it. Within the 30-day active window, the union of these rows is kept for each source/site/hour:

1. The newest snapshot by label and by `fetchedAt`, for the backfills and `HourlyForecastSelector`.
2. The newest non-null value by label for each coalesced field, such as cloud and precipitation
   fields.
3. The newest sufficiently old snapshot carrying the mid/high-cloud band used by prior-day graphs.
4. The newest snapshot captured on the previous local day for rain-accuracy comparisons.
5. The settled pick (`SettledForecastPick`), which history stitching and daily noon cloud read:
   the newest snapshot fetched at or before the hour, or the earliest snapshot when none predates
   it. Also kept, per coalesced field, is the first non-null value in that same window, newest
   first.

Rows needed by any one reader survive even when another reader would not select them. A new reader
of older snapshots must be added to this shared rule and its equivalence tests before pruning can be
considered safe.

## Settled archive

For hours older than the active window, the rule keeps one row per source, forecast hour, and site
cluster: the settled pick (rule 5), with the latest snapshot label breaking `fetchedAt` ties, so an
archived hour draws the same forecast it drew while active. General retention
then deletes settled hours beyond 18 months. This preserves a long forecast-evolution record without
keeping every active-window variant for the same period.

## When pruning runs

Snapshot pruning runs at most once per 24 hours and only during housekeeping conditions: charging
with the screen off. Android enqueues `HistoryPruneWorker` with charging/idle constraints rather than
running the prune inside a fetch. Desktop checks AC/screen state in the daemon and may vacuum after a
prune when useful.

One-time repairs that need every historical snapshot must finish before the first prune; both
platforms retain their completion gates. Pruning is idempotent and uses `HISTORY_PRUNE` diagnostics.

## Key files

- Ages: `shared/.../data/local/RetentionPolicy.kt`
- Keep/settle specification: `shared/.../data/local/HistorySnapshotRetention.kt`
- Snapshot creation: `shared/.../history/HourlySnapshotRun.kt`
- Persistence order: `shared/.../util/ForecastPersistRun.kt`
- Android execution: `WeatherRetentionManager.kt`, `HistoryPruneWorker.kt`, `HistorySnapshotPruner`
- Desktop execution: `DesktopWeatherDao.kt`, `DesktopWeatherRepository.kt`
