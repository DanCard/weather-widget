# Keep blended hourly actuals, trim hourly forecasts

Status: implemented 2026-11-01.

## Goal

Flip the retention of the two hourly datasets, on Android and desktop:

- **Observed history:** archive the blended result for 18 months, in a new `hourly_actuals` table, with
  one row per hour, actuals source and site. Raw `observations` stay short-lived.
- **Forecast history:** trim `hourly_forecasts` after 5 days and delete it once the views stop reaching
  it.

## Evidence (2026-11-01)

Backups: `backups/20261101_105806_*` and a copy of the desktop `weather.db`.

| | Pixel | Samsung | Desktop |
|---|---|---|---|
| `hourly_forecasts` size, including indexes | 14.4 MB | 7.7 MB | ~5 MB |
| `hourly_forecasts` rows | 67,225 | 34,143 | 20,334 |
| Rows per hour, 5-30 days old | 1.26 | 1.5 | 2.1 |
| Forecast rows over 30 days old | 25,054 | 7,896 | 7,426 |
| `observations` size, including indexes | 7.7 MB | 6.0 MB | ~4.7 MB |
| `observations` rows | 21,654 | 19,925 | 15,166 |

Projected at 18 months (extrapolated, not measured):

- Forecast archive if kept: 60-150 MB on the phones.
- Observations at one row per station and hour: about 80 MB.
- `hourly_actuals`: perhaps 7-10 MB, assuming about 5 sources at one site and 100 bytes per row. Travel
  adds sites.

Reader limits found in the code:

| Reader | Reach |
|---|---|
| Hourly pan (`NavigationUtils.MIN_HOURLY_OFFSET`, `DesktopGraphUtils.MAX_BACK_HOURS`) | 720 h |
| `PriorDayCloudRun.PAST_DAYS`, `DailyHistoryMaintenance.FORECAST_ONLY_LOOKBACK_DAYS` | 31 days |
| `RainAccuracyCalculator` (from local midnight of the first day) | 30 calendar days, up to 31 days of age |
| `HourlyForecastLoader` (also feeds the Months view) | 72 h back |
| `DailyBlendRecomputeRun.MAX_DAYS_BACK` | 9 days, plus 24 h of blend context |
| `DailyActualsStore`, `DailyActualsLoader` | Past days come from `daily_history`; observations are read only around today |

No blend is stored today. `NWS_BLEND` has no rows on either phone, and desktop stopped storing it.
Temperature, cloud and rain are all computed at read time.

Existing bugs this fixes:

1. Rain accuracy reads 30 days of observations but Android holds 10, so days 11-30 have no actuals.
2. Android ages observations by `timestamp`; desktop ages them by `fetchedAt`
   (`DesktopWeatherDao.kt:971`), so desktop still holds readings from 2026-10-15.

## Target rules

| Age | `hourly_forecasts` | `observations` | `hourly_actuals` |
|---|---|---|---|
| Future to 5 days | All current keep rules | Raw | None |
| 5-11 days | Settled pick and its field fills, day-ahead rain, cloud band | Raw | None |
| 11-32 days | Same | Deleted once archived, with about 2 days' margin | Archived |
| 32 days to 18 months | Deleted | Deleted | Archived |
| Over 18 months | Deleted | Deleted | Deleted |

Forecasts are deleted at 32 days rather than the agreed 30. Readers reach 31 days of age, so a 30-day
cut would blank the first rain-accuracy day and the oldest part of the cloud curve, and
`PriorDayCloudRun` would keep rewriting rows the next prune deletes.

## Design

### New table: `hourly_actuals`

| Column | Notes |
|---|---|
| `hourMs` | Start of the hour, UTC epoch ms |
| `source` | The display source. Temperature, rain and cloud each resolve a source's feed differently, so this is what guarantees an archived hour equals what that source drew |
| `locationLat`, `locationLon` | Quantized through `LocationMatch`, one row per site cluster |
| `temperature` | Fahrenheit, nullable |
| `precipAmountMm` | Nullable; 0 means measured dry, null means unknown |
| `cloudCover`, `cloudCoverLow`, `cloudCoverMid`, `cloudCoverHigh` | Nullable |
| `archivedAt` | When the row was written |

- Primary key: `(hourMs, source, locationLat, locationLon)`.
- Add a Room entity, DAO and migration, plus the desktop schema migration and exported schema. Test both
  with real SQLite.
- A row with no values is not written.
- Rows are frozen. Inserts ignore conflicts, so later code or weight changes never rewrite an archived
  hour. This matches how `daily_history` is treated.

### Archive: new `:shared` `HourlyActualsArchiveRun`

A shared runner plus a platform `Host`, per AGENTS.md. The host supplies only reads and writes.

For each site cluster, actuals source and whole hour at least `HOURLY_ACTUALS_ARCHIVE_DAYS` (11) old and
not yet archived, it calls the same code the views use:

- **Temperature:** `ActualTemperatureSeriesBuilder.build`'s top-of-hour mark. Sources that borrow actuals
  get their provider's line through `build`, with no `supportsTemperatureActuals` gate.
- **Rain:** `ObservedPrecip.rowsFor` and `ObservedPrecip.byHour`. Forecast-only sources have none.
- **Cloud:** `MetarCloudBlender.fromSiteRows`, the report nearest the hour within 30 minutes.

Inputs are the hour's observations plus their 24 h of context, the settled hourly forecasts (still held
at this age), and the current personal-station weight. Every blend needs that context, which is why raw
rows outlive the 11-day mark.

Eleven days puts the archive past `DailyBlendRecomputeRun` and late station reports.

### Raw observation delete moves out of the per-fetch retention

The archive runs in the prune pass, which on Android needs charging and idle and has no overdue override.
Today's delete runs after every fetch, so keeping it would destroy raw rows the archive has not reached.

- Remove the `observations` delete from `WeatherRetentionManager` and `applyRetention`.
- Delete observations only in `HistoryPruneRun`, after the archive succeeds, older than the archive's
  completed boundary minus `OBSERVATION_ARCHIVE_MARGIN_DAYS` (2).
- Both platforms age by `timestamp`.
- If a pass fails, nothing is deleted and the next pass retries. Raw rows can outlast the target during a
  long stall on Android, but are never lost.

### Forecasts: `HistorySnapshotRetention`

- Add `HOURLY_FORECAST_FULL_DAYS = 5` and `HOURLY_FORECAST_DAYS = 32`. Remove
  `HOURLY_HISTORY_SETTLED_DAYS`.
- Past 5 days, skip rule 1 (newest snapshot). Keep rules 2, 3 and 4.
- Remove the settled-archive tier and its wider `TOLERANCE_DEG` clustering.
- Rows older than `HOURLY_FORECAST_DAYS` are never kept.
- Change the age delete on both platforms to `dateTime < cutoff` only. Today's `OR fetchedAt < cutoff` was
  harmless at 18 months, but at 32 days it could delete a settled pick from a source that was off for a
  while.

### Prune pass: `HistoryPruneRun`

Order: forecast age delete, snapshot prune, actuals archive, observation delete, `hourly_actuals` age
delete, VACUUM, marker. Log counts under `HISTORY_PRUNE`.

The forecast delete cannot remove archive input, because forecasts outlive the 11-day archive age by
3 weeks.

### Readers

A shared `HourlyActualsHistory` merges archived hours with live results, and the live blend wins wherever
raw rows still exist. Archived rows are never converted into `ObservationReading`: that would bring back
the problem this design avoids. Existing observation readers do not change.

| Reader | Change |
|---|---|
| Rain accuracy | Reads actuals for days 11-30 from the merge (Android; desktop if it has this view) |
| Hourly temperature graph | Observed line reaches the 30-day pan limit on both platforms |
| Rain graph | "Act" amounts for archived hours on both platforms |
| Cloud graph | Actuals for archived hours on both platforms |
| Daily and Months views | No change; `daily_history` stays the daily record |

## Audit before coding

1. Confirm no reader needs rule 1 forecast rows older than 5 days: `HourlyForecastSelector`, the
   `HourlyForecastDao` queries and `getHourlyHistory`. Confirm no reader reaches past 32 days.
2. Find which `ActualTemperatureSeriesBuilder` output is the hourly graph's value, so an archived hour
   matches what it drew while live. `blendObservationSeries` emits native timestamps, not hours.
3. Define "actuals source": list the providers and how redirected sources resolve to them
   (`ActualsReadScope`, `redirected_actuals_provider_not_borrower`), and which have rain, cloud or
   temperature.
4. Check how site clusters behave for travel and GPS fragments. Samsung has 1,338 site-hours against the
   Pixel's 241 over the same period.
5. Find any reader of raw observations older than about 13 days, or any fetch gate that treats their
   absence as a gap.
6. Confirm late observation writes never land behind the archive boundary. Desktop shows backfills
   about 7 days old.

## Tests

- `:shared`: `HourlyActualsArchiveRun`:
  - An archived hour equals the live temperature, rain and cloud for the same raw rows.
  - Tomorrow.io five-minute rain, SPECI handling and AO2 measured-dry survive.
  - Unknown stays null.
  - A second pass changes nothing, and a frozen row is not rewritten.
  - A failed archive deletes no observations.
- `:shared`: rewrite the `HistorySnapshotRetentionTest` tier cases for the 5-day and 32-day boundaries and
  remove the settled-archive cases.
- `:shared`: `HourlyActualsHistory` prefers live values and fills only archived hours.
- Keep platforms equivalent: extend `HistoryPruneHostContract`, `HistoryPruneRunTest`,
  `HistorySnapshotPrunerTest`, `DesktopHistoryPruneTest`, `DesktopRetentionPolicyTest` and the Room and
  desktop host contract tests.
- Migration tests with real SQLite on both platforms.
- A guard test ties the archive age to `MAX_DAYS_BACK` and `HOURLY_FORECAST_DAYS` to the reader limits.
- Gate: `./scripts/staggered-tests.sh`.

## Verification

1. Run the pass on copies of the Pixel and desktop databases. Compare archived hours with live values and
   record row counts and file size.
2. Run the debug prune on an emulator. Check the 30-day pan, rain list, cloud graph and logcat.
3. Rebuild and restart the desktop app, then confirm a `HISTORY_PRUNE` pass and the same views.
4. Measure the Pixel's cold read time for the 30-day pan.

## Risks

- **Irreversible on first run:** forecasts past 32 days are deleted on every device. The 2026-11-01
  backups are the only copy.
- **History starts at release:** only raw rows still on the device can be archived, so the 18-month record
  builds from about 11 days before the ship date. Rain accuracy days 11-30 fill in over the next few weeks.
- **No station history past about 13 days:** the Stations screen cannot show older readings.
- **Frozen blends:** a blend fix or personal-station weight change applies only to later hours.
- **Visible change:** the hourly graphs gain observed lines for days 11-30.

## Docs

- `arch/data-pruning.md`: retention table, keep rules, settled archive section, new table and pass order.
- `arch/ARCHITECTURE.md` if it lists tables, and `arch/daily-history-extremes.md` if the audit changes
  anything.
- Stale comments: `RetentionPolicy`, `DailyBlendRecomputeRun`, `SynopticFetchWindow`, `WeatherDatabase`,
  `HistorySnapshotRetention`.
