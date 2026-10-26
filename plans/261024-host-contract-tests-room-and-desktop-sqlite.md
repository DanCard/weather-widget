# Host contract tests: one suite, run on Room and on desktop SQLite

Follows `261023-duplicate-logic-audit-different-names.md` → "Next" item 1 (10-21 audit Tier 3,
option b).

## Status and handoff (2026-10-24, end of session)
**All 11 Hosts done**; `HostContractCoverageTest.PENDING` is empty, so a new runner Host fails the
build until it has a contract extended on both platforms. Details per Host under "Done" below.

**Pattern for a new Host (copy an existing one, e.g. `NowcastRefreshHostContract`):**
1. Read both Host implementations and the DAO SQL behind each read/write; note every difference
   (source scope, site match — exact key / same-site 0.002° / ±0.1° box —, end inclusive, ordering).
2. Divergence → ask the user (recommendation first) before changing code; none → just pin it.
3. Extract each platform's anonymous Host into a class (network/clock passed in as lambdas).
4. Abstract `<Runner>HostContract` in `shared/src/testFixtures/.../shared/contract/`; seeds write raw
   rows (desktop: plain INSERT via `db.getConnection()`, the DAO writers quantize/stamp the clock);
   one case per reading that could differ. Subclasses: `app/src/test` (Robolectric, `TestDatabase`,
   `@Category(LongDuration)`) and `desktop/src/test` (temp-file DB, `@Category(ShortDuration)`).
5. A rule change gets a test that fails on the old code.
6. `./gradlew :shared:test :desktop:test testDebugUnitTest :app:compileDebugAndroidTestKotlin`,
   `installDebug`, `scripts/buildStart-desktop.sh`, crash check, then a Done bullet here + a Progress
   line in the audit plan, commit.

**Open items found, not changed (each needs a user decision):**
- The live-day observation blend's hourly input differs: Android `ObservationResolver` callers vs
  desktop `recomputeDailyExtremes` → `getHourlyWithHistory` of the displayed source.
  Measured 2026-10-25 (replay of the desktop DB through `ActualsAggregator.aggregate`, desktop's
  actuals-provider map installed, two hourly inputs): the aggregator gives each source's row only that
  source's hourly (`sourceHourly`), used for `extrapolateForward` across reading gaps and today's
  forecast-precip fallback. Desktop passes the displayed source's last 72 h only; Android's empty-list
  path passes every source over the day's context window. 8 of 56 day×source rows differed, all on
  days outside desktop's 72 h (10-17, 10-21), lows by ≤ 0.07 °F, highs never; none inside the window.
  Cross-device row comparisons are confounded (Pixel: Silurian → Synoptic, Tomorrow.io own feed;
  desktop: Tomorrow.io → NWS). Not yet measured: Android callers that pass their own hourly list
  (`WidgetDataBundleLoader`).
  **Resolved (user approved 2026-10-25):** `:shared` `ActualsAggregator.aggregateStored` owns the input
  — every source over the observation window, read through a platform lambda (inline, so Room's
  suspend read works). Desktop `recomputeDailyExtremes` uses `getHourlyForecastsAllSources`; Android's
  per-day recompute always reads (the `hourlyForecasts` parameter is gone from the recompute chain, so
  the widget bundle no longer passes its graph list). `DesktopDailyBlendHourlyInputTest` fails on the
  old input (high 60.0 vs < 55). Pixel/Samsung after install: `DAILY_RECOMPUTE_PERF` hourly=222/103 ms,
  extremes as before.
- `HourlyOnDemand.hoursToCover` (both platforms) counts a neighbouring town's rows in the ±0.1° box
  as coverage, so a day tap can skip a fetch only the neighbour has.
- Desktop `NWS_STATION_ACTUALS_OUTCOME requested=1 pulled=0` every run: today's row looks counted
  as missing and then skipped — harmless noise, not investigated.
- The Google Docs copies of this plan and the snapshot plan predate the latest edits.

**Gotchas:** `HourlyProximityQueryAllowlistTest` lists files by name — moving a raw
`getHourlyForecasts` call to a new file needs the entry moved. Desktop `DesktopWeatherRepository`
splices: match on unique text, the same `nowMs = now,` appears several times. The Pixel drops off
adb at times; `ANDROID_SERIAL=<id> ./gradlew installDebug` installs one device.

## Why
Every shared runner reads through a `Host`, and each platform backs each Host read with its own DAO
query (Room `@Query` vs JDBC in `DesktopWeatherDao`, 2,493 lines). The runner tests use fakes, so a
Host whose SQL means something different on one platform passes every test today. Eleven runner Hosts
exist (`DailyHistoryRun`, `GoogleHistoryRun`, `HourlyOnDemandRun`, `CurrentConditionsRun`,
`NowcastRefreshRun`, `NwsStationBundles`, `ClimateNormalsRun`, `WeatherApiHistoryRun`,
`PriorDayCloudRun`, `NwsStationActualsRun`, `ElapsedForecastBackfill.Store`).

## The "known differing read" was a misreading
The 10-21 audit's example — Android `getForecastsInRange` (newest batch) vs desktop
`getForecastsInRangeBySource` (every fetch) — compares two *different* reads. The same-named pair
agrees on content: both `getForecastsInRangeBySource` return every fetch of one source in the box.
They differ only in order (Android `targetDate, dateOfPrediction DESC, batchFetchedAt DESC, fetchedAt
DESC`; desktop no `ORDER BY`), and their one shared caller (`AccuracyBreakdown.compute`, via both
accuracy calculators) picks `maxByOrNull { fetchedAt }`, so only an exact `fetchedAt` tie could tell.
Not a bug; the suite pins it anyway.

## A real one: `DailyHistoryRun.Host.hourlyHistory`
| | Android (`DailyHistorySnapshotter`) | Desktop (`DesktopWeatherRepository`) |
|---|---|---|
| DAO | `getHistoryInRangeForBucketWindow` (bucket ±∞) | `getHourlyHistory` |
| Rows per hour | every snapshot | one, merged: freshest snapshot, nullable fields (cloud) coalesced from older ones |
| End of range | exclusive (`<`) | inclusive (`<=`) |
| `Generic` rows | never | for hours after now |

Readers: the one-time chance backfill and the frozen-display backfill (noon cloud) → `HourlyForecastStitcher`.
~~Where NWS's freshest snapshot omits sky cover, desktop backfills noon cloud from an older snapshot
and Android leaves it null.~~ **Wrong (found while implementing):** the stitcher's `collapse` already
coalesces nullable fields across same-site snapshots, freshest first, so Android got the same noon
cloud. What really differed: the end hour (inclusive on desktop) and `Generic` rows for future hours
— neither reachable by the past-day backfills — and desktop's merge keyed on (hour, source) across
every site in the box, before the stitcher's same-site filter. No visible effect today; the contract
pins it.

**Decision needed (recommendation first):** — decided A, see below.
- **A (recommended)** — the contract is the stitcher's input: every snapshot of [source] only, freshest
  first per hour, end exclusive, no `Generic`. Desktop's Host switches to a raw read; the cloud
  coalescing — a rule, not a query — moves into `:shared` (stitcher or a `HourlyHistoryMerge`) so both
  get it. Noon cloud backfills where the freshest snapshot lacks it, on both.
- B — desktop's merged form is the contract; Android's Host merges with the same shared function.
  Same visible result as A; A keeps SQL dumb and the rule in one place.

## Design
- **`:shared` testFixtures** (already consumed by `:app` and `:desktop` tests; holds `DailyParity`):
  `abstract class DailyHistoryHostContract` with `abstract fun host(): DailyHistoryRun.Host` and a
  `Seed` port (`forecasts(rows)`, `hourly(rows)`, `hourlyHistory(snapshots)`, `dailyHistory(rows)`)
  taking `:shared` model types. Cases are plain `@Test`s on the abstract class, so each subclass runs
  all of them.
- **Hosts become testable classes**: Android `RoomDailyHistoryHost(daos…)` out of
  `DailyHistorySnapshotter`'s anonymous object; desktop `DesktopDailyHistoryHost(weatherDao, clock)` out
  of `DesktopWeatherRepository`'s. No behaviour change in the extraction.
- **Subclasses**: `:app` `RoomDailyHistoryHostContractTest` (Robolectric, `TestDatabase.create()`, as
  `LocationMatchContractTest`); `:shared` `DesktopDailyHistoryHostContractTest` (temp-file
  `DesktopWeatherDatabase`, as `AppLogsContractTest`).
- **Cases (phase 1, `DailyHistoryRun.Host`)**, each a fixture that tells the readings apart:
  - `forecastFetches`: two fetches of one day → both rows; a row 1.5 km away (inside the box) is
    returned, one outside is not; implausible temps nulled; range ends inclusive.
  - `dailyHistory`: site box and inclusive UTC-midnight range.
  - `hourlyForecasts`: every source, box, inclusive ends, ascending.
  - `hourlyHistory`: two snapshots of one hour, freshest lacking cloud (the case above); an hour at the
    exact end; a `Generic` row; another source's row.
  - Writes: `updateForecastOverlay` / `updatePriorForecast` touch only their columns (a row with every
    other column set reads back unchanged); `insertDailyHistory` replaces on the key.
- Then the same pattern Host by Host, smallest DB surface first: `ClimateNormalsRun`,
  `PriorDayCloudRun`, `NwsStationActualsRun`, `HourlyOnDemandRun`, `WeatherApiHistoryRun`,
  `ElapsedForecastBackfill.Store`, the rest. Each one a Progress line in the audit plan; any divergence
  found stops for a decision as above.
- **Guard**: a test in `:desktop` beside `CrossPlatformDuplicateNamesTest` that lists every `interface
  Host` / `interface Store` nested in a `:shared` runner and fails if it has no
  `…HostContract` in testFixtures, against an allowlist seeded with the ten not yet done. New runners
  then can't skip it.

**Decided (user, 2026-10-24): A.** With the stitcher already merging, A needed no new shared
function: desktop's Host reads `DesktopWeatherDao.getHourlyHistorySnapshots` (raw, `[start, end)`,
freshest bucket first); `getHourlyHistory` (merged) stays for desktop's display reads.

## Done (2026-10-24)
- `:shared` testFixtures `DailyHistoryHostContract` (7 cases) + `testFixturesImplementation(junit)`.
- Android `RoomDailyHistoryHost` (out of `DailyHistorySnapshotter`), desktop `DesktopDailyHistoryHost`
  (out of `DesktopWeatherRepository`; marker tags moved with it). No behaviour change besides the
  desktop `hourlyHistory` read.
- `RoomDailyHistoryHostContractTest` (`:app`, Robolectric) and `DesktopDailyHistoryHostContractTest`
  (`:desktop`, temp-file SQLite): 7/7 each. The `hourlyHistory` case fails on the old desktop read
  (1 of 7).
- `:desktop` `HostContractCoverageTest`: every `interface Host|Store` in a `:shared` class needs an
  abstract contract in testFixtures extended in both `app/src/test` and `desktop/src/test`; 10 ports
  in `PENDING`, which only shrinks.
- `ClimateNormalsRun.Host` → `ClimateNormalsHostContract` (4 cases: empty cache, month needing both
  sides, other sites dropped, same site replaced) on `RoomClimateNormalsHost` / `DesktopClimateNormalsHost`;
  no divergence. 9 pending.
- `PriorDayCloudRun.Host` → `PriorDayCloudHostContract` (5 cases: store → each platform's
  `getPriorDayCloudForecast`). **Divergence, decided (user, 2026-10-24): Android's rule.** Desktop's read
  merged every site in the ±0.1° box (a neighbouring town's curve could fill this site's hours) and
  included the end hour; Android read this site only, end exclusive. Both now reduce through `:shared`
  `PriorDayCloudForecast.coverByHour` (same-site rows; between two fragments the one nearer the centre —
  Android's had been the last in `lat, lon` order). The old desktop read fails 2 of 5. No change on
  today's data (desktop's site has one row per hour). Hosts `RoomPriorDayCloudHost` /
  `DesktopPriorDayCloudHost`; throttle memory `PriorDayCloudRun.LastAttempt`. 8 pending.
  Desktop `getPriorDayBandForecast` (mid/high band curve) had the same gap (box, end hour included);
  now `:shared` `PriorDayBandForecast.selectAtSite` on both, `[start, end)` (user: Android's rule
  there too). Tests: `PriorDayBandForecastTest`, `DesktopWeatherDaoTest` (fails on the old query).
- `NwsStationActualsRun.Host` → `NwsStationActualsHostContract` (6 cases: `dailyHistory`, `upsert`,
  `nwsLiveHourly`, `nwsHourlyHistory`, the day's stitched NWS hourly, `storedObservations`).
  **Divergence, decided (user, 2026-10-24): live + history fill.** The past-day blend's NWS hourly was
  live rows collapsed to one site on Android (`unifyToNearestSite`), live + history snapshots on
  desktop (`getHourlyWithHistory`). Now the Host has two raw reads and the shared run stitches them
  (`NwsStationActualsRun.nwsHourlyForDay`, `HourlyForecastStitcher`: live wins every hour it has).
  No change on today's data (live NWS rows cover every past hour in the lookback). Observations
  already matched (`ObservationSiteMerge` on both). Hosts `RoomNwsStationActualsHost` /
  `DesktopNwsStationActualsHost`; `NwsStationActualsStore` takes `HourlyForecastHistoryDao`. 7 pending.
  Noticed, not changed: the live-day blend's hourly input differs too (Android
  `ObservationResolver` callers vs desktop `recomputeDailyExtremes` → `getHourlyWithHistory` of the
  displayed source) — not a Host read.
- `HourlyOnDemandRun.Host` → `HourlyOnDemandHostContract` (`storedHourly`: one source, the whole ±0.1°
  box, an hour back through `REACH_HOURS`, both ends inclusive) on `RoomHourlyOnDemandHost` /
  `DesktopHourlyOnDemandHost` (fetch passed in); no divergence. 6 pending. Noticed, both platforms:
  a neighbouring town's rows in the box count as coverage for `hoursToCover`, so a tap could skip a
  fetch when only the neighbour has that day — pinned as today's behaviour, not changed.
- `WeatherApiHistoryRun.Host` → `WeatherApiHistoryHostContract` (3 cases: `storedObservations`, store →
  read-back + the day reads as covered, `latestResult` by prefix) on `RoomWeatherApiHistoryHost` /
  `DesktopWeatherApiHistoryHost` (fetch, and Android's extremes recompute, passed in). Desktop's
  `storedObservations` read every api; now WeatherAPI only, as Android (no behaviour change: `decide`
  already filtered). 5 pending. **Was open, resolved below (storage, both directions):** desktop wrote every observation
  at raw device coordinates (`37.4166014`), Android quantizes (`37.417`). Reads agree (box +
  `ObservationSiteMerge`); keys differ, so a jittered fix on desktop would add rows rather than
  replace them. **Decided (user, 2026-10-24): quantize on desktop too** ("Desktop is a misnomer.
  Could be a laptop, something that moves around"). `upsertObservations` keys on
  `LocationMatch.quantize`; `getLatestObservationFetchedAt` matches the site
  (`JDBC_SAME_SITE_WHERE`), not exact raw coordinates, which would otherwise miss and refetch at every
  launch; `DesktopWeatherDatabase.initialize` moves raw-key rows onto the quantized key (idempotent,
  `UPDATE OR REPLACE`). Fixed with it: desktop's `touchLatestObservationFetchedAt` (quantized key,
  10-23) never matched a raw row, so the touch was a no-op. Tests in `DesktopWeatherDaoTest` (3 fail
  on the old code). The desktop DB's 12,937 rows moved, none lost.
- `ElapsedForecastBackfill.Store` → `ElapsedForecastBackfillStoreContract` (2 cases: `coveredHours` =
  this source, this site, any bucket, `[start, end)`; insert → covered, a second backfill stores
  nothing) on `RoomElapsedHistoryStore` / `DesktopElapsedHistoryStore` (bucket passed in); no
  divergence. 4 pending. **Was open, resolved** by `HourlySnapshotRun` (`90066cdb`,
  `performance/261024-hourly-snapshot-windows-by-view-and-battery.md`): the snapshot bucket itself — Android `ForecastHistoryPolicy` (in
  `:app`) files a displayed source every 4 h and a background one every 12 h ("DB-write savings");
  desktop filed every source every 4 h, hard-coded in three places.
- `NwsStationBundles.Host` → `NwsStationBundlesHostContract` (2 cases: `touchLatest` stamps only this
  station's newest reading on this site's quantized key — not its older ones, another town's, a jitter
  fragment's or another station's; nothing stored here → no change) on `RoomNwsStationBundlesHost` /
  `DesktopNwsStationBundlesHost` (clock passed in); no divergence. Desktop's half passes only since the
  quantized observation keys (`47a868a4`): before, its rows were raw-keyed and the stamp matched none.
  3 pending (GoogleHistoryRun, CurrentConditionsRun, NowcastRefreshRun).
- `NowcastRefreshRun.Host` → `NowcastRefreshHostContract` (`hourly`: every source's live rows in the
  read box, both ends inclusive). **Divergence, decided (user, 2026-10-24): usable sources only.**
  Android counted every row in the box toward "rain plausible?" — a source turned off, or the Generic
  filler, could trigger the ~1 KB request; desktop read only its usable sources. Now both Hosts return
  the same raw rows plus `usableSourceIds`, and the run filters (`NowcastRefreshRunTest`: a disabled
  source's 90 % no longer fetches). Hosts `RoomNowcastHost` / `DesktopNowcastHost`. 2 pending
  (GoogleHistoryRun, CurrentConditionsRun).

## Verification
- The `hourlyHistory` case fails on today's code on one side (which side depends on the decision) —
  checked before the fix, as required.
- `./gradlew :shared:test :desktop:test testDebugUnitTest :app:compileDebugAndroidTestKotlin`.
- On device: no visible change expected; the backfills are one-time and already marked done on both
  phones and desktop. Pull the Pixel DB and check `DAILY_HISTORY_UPKEEP` still logs `ran=true` after
  install (Host extraction didn't break the wiring), and the same on desktop after restart.

## Not in scope
SQLDelight (option a). Collapsing the two DAOs into one. Reads that no runner Host goes through
(Activities' direct DAO calls) — they're covered as their flows move into runners (B rest).
