# One hourly forecast table

User, 2026-11-01: "What do you think of getting rid of the live hourly table and only having one table
for hourly rows?" and "I value simplicity and correctness over speed." Follows
`plans/261101-every-fetch-saves-an-hourly-snapshot.md`, which made history hold every fetch.

Plan only. No code changes until approved.

## Goal
One table, append-only per fetch. A fetch adds rows and never changes one, so no fetch can alter what an
earlier fetch predicted, for any source, on either platform. One rule answers every "which row stands for
this hour?" question: `SettledForecastPick`.

## Today
| | `hourly_forecasts` (live) | `hourly_forecast_history` |
|---|---|---|
| Key | hour, source, site | hour, source, site, label |
| Write | `INSERT OR REPLACE` | append |
| Horizon | up to 8 days | 72 h |
| Rows: Samsung / desktop | 12,166 / 4,529 | 27,533 / 48,613 |
| Retention | 30 days | `HistorySnapshotRetention` |

Reads of the live table: 24 Android call sites and 3 desktop DAO queries. Most of them take raw rows and
reduce them in memory, some through `HourlyForecastStitcher` and some not, so two callers can answer the
same question differently.

## Design
**Table.** Keep `hourly_forecast_history`'s columns and key, and name the table `hourly_forecasts`: it is
the only one, so "history" stops saying anything. Keep `timestampToGroupPredictions`, because rows from
before 2026-10-24 carry window labels that readers still use; removing it is a later, separate decision.

**Entity.** `HourlyForecastEntity` gains the label as a last parameter defaulting to `fetchedAt`, so the
tests that build it positionally keep compiling. `HourlyForecastHistoryEntity` goes.

**Write.** `ForecastPersistRun` appends every hour of the fetch up to the one horizon, labelled with the
fetch time. Removed, because the rule makes each unnecessary:
- `HourlySnapshotRun` and its Hosts: every fetch is saved, and one write means no label to share.
- `HourlyForecastWrite.merge` and its stored read: `backfillOrder` already fills a null field at read
  time, nearest fetch first. Rows then say only what that fetch said.
- `ElapsedForecastBackfill`: elapsed hours are simply appended, and `SettledForecastPick` already reads
  them only when nothing predates the hour, taking the earliest, which is what the backfill stored.
- `HourlyOnDemand.Keep`'s two horizons and the snapshot use of `ROUTINE_HOURS`.

**`fetchedAt`.** "Last confirmed" stays true without redefining it: the newest row for an hour is the last
fetch that returned it, so `HourlyOnDemand`'s coverage check reads the newest row per hour.

**Read.** One shared `:shared` read, with the platforms supplying only the SQL: rows in a range for a site
and sources, reduced by the stitcher. Each read site is classified as one of:
1. Forecast as it stood: the settled pick, which is what most want.
2. Newest row regardless of hour: freshness and coverage checks.
A caller that needs neither gets a named reason in the code, not a third silent rule.

**Retention.** `HistorySnapshotRetention` stays the specification. Delete the 30-day `fetchedAt` delete on
the live table. Rule 1 keeps the newest row per hour, so far-future hours stay readable.

**Index.** Replace both tables' indexes with one covering site, source, hour, `fetchedAt`.

## Correctness risks
1. A reader that relied on live "latest wins" for a started hour would change answer. The audit in
   phase 1 exists to find these. `HourlyStaleFragmentCollapseTest` guards the stale-fragment case.
2. Migration runs on real databases of about 28 MB. Live rows are copied with `INSERT OR IGNORE`, label =
   `fetchedAt`, then the old table is dropped and the other renamed. No row may be lost: test on copies of
   the current Samsung and desktop databases, not only fixtures. Backups first.
3. One-shot repairs (`FrozenRainChanceRepair`, frozen-display and chance backfills) read all snapshots and
   must still run before the first prune.
4. Daily-history freeze, rain accuracy and prior-day bands must give identical values before and after;
   compare them on the same database copies.

## Speed
Not a gate. Compare `hourlyMs` in `DAILY_INTERACTION_PERF` before and after, and tune the index if a real
widget interaction gets noticeably slower.

## Phase 1 audit (2026-11-01)
The audit made phase 1 smaller than the Read section above. Most raw readers already want the newest row
per hour, so in phase 3 the DAO query itself returns that and they need no change. No new read layer: the
stitcher is already the shared rule, over each platform's SQL. `HourlyProximityQueryAllowlistTest` keeps
each raw reader's reason.

| Rule | Readers | Phase 1 |
|---|---|---|
| Settled, already | `HourlyForecastLoader`, `GraphDataLoader` graph and current temp, `WidgetStartupCoordinator`, `DailyInteractionRenderer`, `DailyHistoryRun`, `NwsStationActualsRun`, desktop `getHourlyWithHistory` | none |
| Settled, was wrong | Android observations table (desktop was settled); header reserve on both platforms (the rain % it reserves for is settled) | fixed |
| Newest per hour | `DataFreshness`, `SourceStalenessProbe`, `NoHourlyDayClickCoordinator`, `UIUpdateScheduler`, `NowcastRefresher`, `DailyActualsLoader`, daily blend hosts, forecast-history look-ahead | none |
| Write path | `HourlyForecastStore`, both persist Hosts, `DesktopSourceFetchHost` | phases 3 and 4 |
| Dead | `CurrentTempRepository.getInterpolatedTemperature`, `getNextInterpolationUpdateTime` | deleted |

Open for phase 3: `HourlyStaleFragmentCollapseTest`'s note that the current hour stays live no longer
matches `SettledForecastPick`, which freezes it; the desktop blend host does not collapse sites as
Android's does.

## Phase 3 notes (2026-11-01)
- `HourlySnapshotRun` went here, not in phase 4: its 5-minute label reuse would have let a second fetch
  replace the first fetch's rows. The label is now the fetch time.
- `HourlyForecastHistoryEntity` stays as a keyless read/write shape until phase 4, so `deleteRows` goes through
  `HourlyRowKey`: Room would otherwise match every column, and `= NULL` matches nothing, so no row with an
  empty field could be pruned.
- The synthetic `OPEN_METEO_PRIOR24` series shares the table; the two all-sources reads exclude it.
- The 30-day live retention delete is gone: on one table it would have removed settled history.
- Real database copies, before any device: integrity ok, row counts exact (desktop 52,360, Samsung 38,396,
  Pixel 69,846), merge under 50 ms. About 2,000 older rows per database read newer than the old live row; all
  are before 2026-10-31 under window labels, with identical temperature and rain chance.

## Value comparison and phase 4 decisions (2026-11-01)
Old tables against the merged table on real copies, through the real stitcher and rain resolver:
- Desktop and Pixel: 0 hours and 0 of 161 day/night pairs changed.
- Samsung, at its real site: 150 of 2,651 past hours and 4 day/night pairs changed, up to 4.0 F, 70 over 1 F; no future
  hour. In all 150 the old stitch borrowed a nearby fragment's row while a same-site row existed, because
  `groupSameSite` applied its fallback to live and history separately. The merged result follows the documented
  rule that a same-site row beats a neighbour. Accepted.
- Prior-day reads see 18 to 57 changed hours per database: fetches the old skip rule never saved now count.

Kept, against the Design section above:
- `ElapsedForecastBackfill`: appending elapsed hours would make post-hour rows the newest, and retention keeps the
  newest, so newest-per-hour readers would see revised past hours. Its gate is what prevents that.
- `HourlyForecastWrite.merge`: newest-row readers, including daily summaries, have no read-time fill.

Removed: `HourlyForecastHistoryEntity` and `HourlyRowKey`. `HourlyForecastHistoryDao` remains as the reader of every
fetch's rows.

## Phases
Each phase is its own commit and leaves both platforms working.

1. **One read rule.** Classify every read site (audit above) and fix the ones on the wrong rule. No schema
   change.
2. **One horizon.** Save snapshots to the full horizon. Remove the snapshot limit.
3. **One table.** Room migration, desktop schema migration, exported schema, index, entity. The live
   writes stop here.
4. **Remove the rest.** `HourlyForecastHistoryEntity` (see the decisions above for what stayed).
5. **Docs.** `arch/ARCHITECTURE.md`, `arch/data-pruning.md`, `arch/hourly-forecast-update-cadence.md`.

## Tests
1. Property on the replay in `PreHourForecastSurvivesTest`: after any sequence of fetches, every earlier
   fetch's rows are unchanged.
2. Migration: real SQLite on both platforms, row counts and picks equal before and after.
3. Host contract: the shared read returns the same on Room and desktop SQLite.
4. `./scripts/staggered-tests.sh`, confirming `:desktop` compiled in the run's own output.
5. Runtime: desktop app, then the emulator, then the phones with widgets intact; screenshots of daily,
   temperature, rain and cloud views before and after on each.
