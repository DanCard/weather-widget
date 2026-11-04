# Remove the hourly pre-hour freeze everywhere

**User, 2026-11-04:** "lets get rid of the freeze rule" → scope "Everywhere" (after being shown the
hindcast, archive, header-delta, and pruning consequences). Reverses "Freeze wins always" (2026-10-27,
plans/261027-pre-hour-freeze-retention-and-shared-pick.md).

## Evidence

Desktop asleep 19:33–03:10 drew NWS 3a = 4% (18:45 fetch) while its own 03:10 fetch said 25%; the
Fold drew 25%. `SettledForecastPick` froze the started hour at the newest fetch at or before it.

## New rule

`SettledForecastPick.pick` / `backfillOrder`: **every hour, started or not, takes the newest fetch**;
null fields borrow from the next-newest rows. One rule for all readers, so they stay consistent:

| Reader | Effect |
|---|---|
| Hourly graphs (temp, rain, cloud; both platforms) | past hours show the newest fetch; a source's in-hour/after-hour value (hindcast) replaces the forecast |
| `DailyHistoryRun` / `DailyHistoryMaintenance` (archived rain chance, noon cloud) | archived from newest rows; already-frozen `daily_history` columns are not rewritten |
| `CurrentTemperatureResolver` (header delta) | current hour's forecast follows the latest fetch |
| `NwsStationActualsRun` | NWS hourly for a day = newest rows |
| `DailyNoonCloudCover`, `CloudSeriesBuilder` | noon / today's elapsed hours = newest row |
| `HistorySnapshotRetention` (prune) | rule 2 collapses into rule 1 (newest); **pre-hour rows no longer kept** |

Unchanged: rules 3 (prior-day band) and 4 (rain accuracy, previous-day capture) still keep their rows,
so the day-ago cloud curve and rain-accuracy stats survive. The daily `forecasts` snapshot hindcast
cutoffs (`ForecastSnapshotHindcastCutoff`, past-day overlays) are a separate mechanism, not touched.
`StaleForecastSpans` dimming stays: stale = newest fetch older than 3 h at the hour's start.

## Irreversible step

The first prune after install deletes pre-hour rows that no rule keeps, on every device. Before
installing: `python3 scripts/backup_databases.py` (phones) and copy
`~/.local/share/weather-widget/weather.db`.

## Changes

1. `SettledForecastPick`: newest-first for every hour; drop `nowMs` from `pick`/`backfillOrder` and
   callers (stitcher, retention, noon cloud, current temp). Rewrite KDoc.
2. `HourlyForecastStitcher`, `HistorySnapshotRetention`, `DailyNoonCloudCover`, `CloudSeriesBuilder`,
   `CurrentTemperatureResolver`, `DailyHistoryRun` comments: describe the newest-fetch rule.
3. `arch/data-pruning.md` rule 2, `arch/hourly-forecast-update-cadence.md` started-hours section.

## Tests

1. `SettledForecastPickTest`: started hour takes the newest (hindcast) fetch; desktop case 18:45 vs
   03:10 → 03:10; null-field backfill newest-first. Fails on old code.
2. Update every freeze-asserting test (listed by grep: stitcher, retention + SQL equivalence, noon
   cloud, current temp settled hour, daily history run, NWS actuals, cloud series, desktop DAO/
   repository, `PreHourForecastSurvivesTest`): assert the newest fetch; delete tests whose only
   purpose was the freeze.
3. `./scripts/unit-tests.sh`; `./scripts/emulator-tests.sh -c` for the migration/prune instrumented
   test if it asserts pre-hour retention.

## Verification

1. Backups first (above). Install; restart desktop.
2. Desktop NWS rain graph: 3a = 25% (03:10 fetch); 1a/2a unchanged (no newer fetch), still dimmed.
3. Prune log line after the next prune; spot-check that a past hour still has its newest row.
