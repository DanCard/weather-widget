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
| `hourly_forecasts` | 30-day active window, then one settled row per source/site/hour for 18 months |
| `forecasts`, `current_status`, `station_cache` | 30 days |
| `observations` | 10 days |
| `api_usage_stats`, desktop `network_usage` | 90 days |
| `source_view_days` | 30 days |
| `app_logs` | 72 hours plus row caps; permanent desktop backfill markers are exempt |
| `climate_normals` | Current location only |

Android runs retention through `WeatherRetentionManager`; desktop runs it through
`DesktopWeatherDao.applyRetention()`. Saved configuration and API keys are preferences/configuration,
not weather rows, and are not erased by this schedule.

## Which hourly snapshots are written

`hourly_forecasts` is append-only: every fetch of every source adds its hours under its own fetch time, and no
fetch changes a row an earlier fetch wrote. A "snapshot" below means one fetch's rows.

- Hours older than the elapsed boundary are not appended, unless the site has no row for that hour
  (`ElapsedForecastBackfill`). Otherwise a fetch's revised past hours would become the newest rows.
- The hour in progress is appended; the settled pick still reads the last forecast made before it.

`ForecastPersistRun` coordinates the hourly, elapsed-backfill, and daily writes so both
platforms use the same order.

## Active-window prune rule

`HistorySnapshotRetention` is the specification; Android and desktop SQL pruning are tested against
it. Within the 30-day active window, the union of these rows is kept for each source/site/hour:

1. The newest snapshot by label and by `fetchedAt`, for the backfills and `HourlyForecastSelector`.
2. The settled pick (`SettledForecastPick`), which history stitching and daily noon cloud read:
   the newest snapshot fetched at or before the hour, or the earliest snapshot when none predates
   it. Also kept, per coalesced field, is the first non-null value in that same window, newest
   first.
3. The newest sufficiently old snapshot carrying the mid/high-cloud band used by prior-day graphs.
4. The newest snapshot captured on the previous local day for rain-accuracy comparisons.

Rows needed by any one reader survive even when another reader would not select them. A new reader
of older snapshots must be added to this shared rule and its equivalence tests before pruning can be
considered safe.

## Settled archive

For hours older than the active window, the rule keeps one row per source, forecast hour, and site
cluster: the settled pick (rule 2), with the latest snapshot label breaking `fetchedAt` ties, so an
archived hour draws the same forecast it drew while active. General retention
then deletes settled hours beyond 18 months. This preserves a long forecast-evolution record without
keeping every active-window variant for the same period.

## When pruning runs

`HistoryPruneRun` is one housekeeping pass, in the same order on both platforms: the 18-month age
delete on hourly rows, the snapshot prune, a `VACUUM` at 8 MB or more free, then the completed marker.
It runs at most once per 24 hours and never inside a fetch. `HistoryPruneSchedule` decides when.

- **Android:** a sync enqueues `HistoryPruneWorker`, which WorkManager holds for charging and device
  idle. There is no override.
- **Desktop:** `DesktopHousekeepingLoop` asks every 15 minutes, independent of refreshes and of the
  away rule, because it uses no network. It runs on AC with the screen off. A prune that is 3 days
  overdue, or has never run, ignores the screen but still needs AC. A failed `VACUUM` is logged and
  retried by a later pass.

The other tables' retention still runs after each fetch.

Do not tie this work to a refresh on desktop. Refreshes stop an hour into being away, so a prune that
needed both a refresh and the screen off stalled for weeks.

One-time repairs that need every historical snapshot must finish before the first prune; both
platforms retain their completion gates. Pruning is idempotent. `HISTORY_PRUNE` logs each pass and,
at most once a day, `skipped=waiting_for_idle` or `overdue=true`, so a stall is visible. Desktop's
`HISTORY_PRUNE_LAST` is a one-row marker exempt from the 72-hour log window.

## Key files

- Ages: `shared/.../data/local/RetentionPolicy.kt`
- Keep/settle specification: `shared/.../data/local/HistorySnapshotRetention.kt`
- Hourly writes: `shared/.../util/ForecastPersistRun.kt`, `shared/.../util/HourlyForecastWrite.kt`
- Persistence order: `shared/.../util/ForecastPersistRun.kt`
- One pass and its schedule: `shared/.../data/local/HistoryPruneRun.kt`, `HistoryPruneSchedule.kt`
- Android execution: `HistoryPruneWorker.kt`, `HistorySnapshotPruner`
- Desktop execution: `DesktopHousekeepingLoop.kt`, `DesktopWeatherRepository.kt`, `DesktopWeatherDao.kt`
