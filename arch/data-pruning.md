# Data retention and snapshot pruning

Android and desktop share retention rules from `:shared`, but execute them through their own storage
layers. Two mechanisms are involved:

- **Age-based retention** bounds how long each table remains available.
- **Hourly housekeeping** removes forecast variants that no reader can select, then archives the
  blended observed result of older hours so raw observations can go.

## Age-based retention

The authoritative durations are in
`shared/src/main/kotlin/com/weatherwidget/data/local/RetentionPolicy.kt`.

| Data | Retention |
|---|---|
| `daily_history` | 18 months |
| `hourly_actuals` | 18 months |
| `hourly_forecasts` | 32 days by forecast hour; fewer snapshots past 5 days |
| `forecasts`, `current_status`, `station_cache` | 30 days |
| `observations` | Until archived, about 13 days; by reading time |
| `api_usage_stats`, desktop `network_usage` | 90 days |
| `source_view_days` | 30 days |
| `app_logs` | 72 hours plus row caps; permanent desktop backfill markers are exempt |
| `climate_normals` | Current location only |

Android runs retention through `WeatherRetentionManager`; desktop runs it through
`DesktopWeatherDao.applyRetention()`. Saved configuration and API keys are preferences/configuration,
not weather rows, and are not erased by this schedule. `hourly_forecasts`, `hourly_actuals`, and
`observations` are deleted only by the housekeeping pass below.

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

Rule 1 applies only to hours under 5 days old. Rules 2, 3, and 4 apply until the hour is deleted at 32
days, which outlasts the readers: the 720-hour pan, rain accuracy's 30 calendar days, and the prior-day
cloud curve's 31 days. The age delete goes by forecast hour alone, so a settled pick from a source that
was off for weeks is not lost early.

Rows needed by any one reader survive even when another reader would not select them. A new reader
of older snapshots must be added to this shared rule and its equivalence tests before pruning can be
considered safe.

## Hourly actuals archive

`HourlyActualsArchiveRun` writes `hourly_actuals`: one frozen row per hour, display source, and site,
for local days wholly past 11 days old. Eleven days clears `DailyBlendRecomputeRun`'s 9-day reach and
its context. Each value comes from the code the views call, so an archived hour is the hour they drew:

- Temperature: `ActualTemperatureSeriesBuilder.build`'s top-of-hour mark.
- Rain: `ObservedPrecip.byHour`. 0 is measured dry; null is unknown.
- Cloud: `MetarCloudBlender.fromSiteRows`, the report nearest the hour, with its bands.

Rows are keyed by display source because each value resolves its feed differently. Inserts ignore
conflicts, so a later blend fix or personal-station weight change does not rewrite an archived hour.

Archived rows are not observations. They are never read back through `ObservationReading` or
re-blended. `HourlyActualsHistory` puts them behind the live series, and the live series wins wherever
raw rows remain. The hourly temperature, rain, and cloud graphs and rain accuracy read them;
`daily_history` stays the daily record. Station-level detail ends with the raw rows.

## When pruning runs

`HistoryPruneRun` is one housekeeping pass, in the same order on both platforms: the forecast age
delete, the snapshot prune, the actuals archive, the raw observation and `hourly_actuals` age deletes, a
`VACUUM` at 8 MB or more free, then the completed marker. Raw observations are deleted only here, behind
what the archive finished plus 2 days: blends read 24 hours either side of an hour, and the pass can
wait days for a charger. A failed archive deletes nothing. The pass runs at most once per 24 hours and never inside a fetch. `HistoryPruneSchedule` decides when.

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
- Actuals archive: `shared/.../data/local/HourlyActualsArchiveRun.kt`, `shared/.../data/model/HourlyActualsHistory.kt`
- Hourly writes: `shared/.../util/ForecastPersistRun.kt`, `shared/.../util/HourlyForecastWrite.kt`
- Persistence order: `shared/.../util/ForecastPersistRun.kt`
- One pass and its schedule: `shared/.../data/local/HistoryPruneRun.kt`, `HistoryPruneSchedule.kt`
- Android execution: `HistoryPruneWorker.kt`, `HistorySnapshotPruner`
- Desktop execution: `DesktopHousekeepingLoop.kt`, `DesktopWeatherRepository.kt`, `DesktopWeatherDao.kt`
