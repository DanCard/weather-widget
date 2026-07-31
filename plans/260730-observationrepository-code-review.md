# ObservationRepository correctness and structural review

**Date:** 2026-07-30
**Status:** Implemented and verified
**Requested path:** `app/src/main/java/com/weatherwidget/widget/data/repository/ObservationRepository.kt`
**Live path reviewed:** `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`

## Executive summary

`ObservationRepository` is not ready to remain as one 957-line class. It currently owns six
different concerns:

1. NWS station discovery and a SharedPreferences station cache.
2. Latest-observation fetching, retry timing, NWS/Synoptic resolution, persistence, and IDW output.
3. Two historical NWS backfill workflows.
4. Daily-actual reads and daily-history recomputation.
5. Observation-list queries.
6. Read-time construction of the synthetic `NWS_BLEND` current observation.

The review found four live correctness problems. Two can produce wrong or missing displayed data,
one breaks structured cancellation, and one leaves a public method contract unimplemented. The
responsibility split below is therefore part of the required remediation, not optional cleanup:
the duplicated and interleaved code is directly contributing to inconsistent error handling,
location scoping, and quality-control behavior.

The existing time-aligned daily blending, frozen-column preservation, proximity filtering,
coordinate quantization, fetch-both policy, and sparse/verbose logging decisions should be
preserved. Several older plans addressed those behaviors already; this review does not repeat their
completed findings.

## Evidence reviewed

### Current code and contracts

- `ObservationRepository.kt` (957 lines), all methods and dependencies.
- `ObservationDao.kt`, including the `(stationId, timestamp)` primary-key assumptions used by its
  queries and `touchLatestFetchedAt`.
- `ObservationEntity.kt`, `DailyHistoryDao.kt`, `DailyHistoryEntity.kt`, and `LocationMatch`.
- `ObservationResolver.kt`, `ActualsAggregator`, `ActualTemperatureSeriesBuilder`,
  `SpatialInterpolator`, `LatestObservationMerge`, `ObservationFallbackPolicy`, `FetchOutcome`,
  `NwsApi`, and `SynopticApi`.
- `WeatherRepository`, `CurrentTempRepository`, `WeatherWidgetWorker`,
  `WidgetStartupCoordinator`, `WeatherObservationsActivity`, and hourly-backfill call sites.
- Hilt construction in `AppModule.kt`.

### Existing tests

- `WeatherRepositoryNwsParallelTest`
- `ObservationRepositoryDailyMergeTest`
- `ObservationRepositoryTodayPrecipTest`
- `PastDayCoverageTest`
- `DailyLiveTodayWindowConsistencyTest`
- `YesterdayActualHighConsistencyTest`
- `ObservationDaoTouchTest`
- `WeatherObservationsActivityRobolectricTest`
- Shared QC, fallback, merge, and actual-series tests

The current tests cover individual blend helpers, daily-history preservation, basic station
parallelism, and UI rendering of a pre-seeded QC row. They do not cover repository cancellation,
QC-safe per-station selection in `getMainObservationsWithComputedNwsBlend`, multi-location
observation replacement/touching, or the `activeSourceList` contract.

### Review verification performed

- The repository complexity audit was run against the current repository package; the metrics used
  in finding 5 are from that result.
- 50 focused existing tests passed across the eight app classes and three shared classes listed in
  the verification matrix, with zero failures or errors.
- At the review-only boundary, `HEAD` equaled `origin/main` at `85f47a3f` and the review plan was the
  only working-tree change.
- The user's later `implement` request explicitly authorized the transition from review-only work
  to the production, test, schema, and DI changes described below.

### Prior plans and history checked

- `260721-observation-location-fragmentation-fix.md` — coordinate quantization and authoritative
  backfill locations are implemented; do not reopen those completed changes.
- `260721-fetch-both-nws-web-prefer-newest.md` — latest-observation fetch-both behavior, API tie
  preference, QC storage, and backfill-only fallback boundaries.
- `260713-observation-fetch-error-hardening.md` — `FetchOutcome` deliberately distinguishes
  definitive no-data from transport/parse failure.
- `260704-daily-history-self-sufficient-daily-view.md` — recomputation must preserve all frozen
  forecast columns during Room `REPLACE`.
- `260516-Investigate-Temp-Discrepancy.md` and the subsequent daily/hourly consistency work —
  time-aligned blending is established behavior.
- The completed `ForecastRepository` split (`cf21db7f`) — precedent for retaining a small public
  repository facade while extracting cohesive persistence and fetch collaborators.

## Findings

### 1. High — remote-call wrappers consume coroutine cancellation

**Evidence**

- `getSortedObservationStations` calls the suspend
  `nwsApi.getObservationStations(...)` inside `runCatching` and converts every throwable to an
  empty station list (`ObservationRepository.kt:301-320`).
- Both backfill entry points call suspend `nwsApi.getGridPoint(...)` inside `runCatching` and
  convert every throwable to `null` (`:360`, `:482`).
- Both station-history loops catch `Exception` immediately around suspend
  `nwsApi.getObservations(...)` and convert it to an empty list (`:378-382`, `:511-515`).
- The outer loops do rethrow `CancellationException`, but those outer handlers cannot see a
  cancellation already consumed by the inner `runCatching`/`catch (Exception)`.
- `NwsApi` and `SynopticApi` are suspend/network APIs, and adjacent repository code already treats
  cancellation as terminal. The project also has explicit cancellation-propagation regressions for
  `WeatherApiHistoryBackfiller`, `ForecastRepository`, widget startup, and interaction routing.

**Impact**

A cancelled WorkManager or widget coroutine can continue through fallback work, DAO writes,
daily-history recomputation, and completion logging instead of terminating. This violates
structured concurrency and is particularly unsafe in this app because worker cancellation already
has a documented native-crash history. It also misclassifies cancellation as “no grid point,” “no
stations,” or an empty NWS response.

**Required change**

1. Never use unqualified `runCatching` around suspend remote/DAO calls.
2. In each remote boundary, explicitly rethrow `CancellationException` before handling ordinary
   failures.
3. Preserve the actual exception in the ephemeral error log; persist a sparse failure summary where
   the workflow already has a queryable failure tag.
4. Centralize this behavior in the extracted NWS source/gateway so latest and both backfill paths
   cannot drift.
5. If station-list refresh fails and a decodable expired cache exists, return that last-known-good
   list with a sparse “stale cache used” breadcrumb instead of throwing away useful station
   metadata. A successful, authoritative empty response remains empty.

**Regression criteria**

- Cancellation from grid-point lookup propagates from both backfill entry points.
- Cancellation from station-list lookup propagates.
- Cancellation from `getObservations` propagates and performs no later insert/recompute/done log.
- An ordinary transport failure is logged and follows the intended fallback/next-station behavior.
- Expired cached stations remain usable when refresh fails, but not when no cache exists.

### 2. High — current NWS blend discards a station when its newest row failed QC

**Evidence**

`getMainObservationsWithComputedNwsBlend` currently:

1. reads all recent NWS rows;
2. filters by timestamp;
3. groups by station;
4. selects each station’s newest row, regardless of `qcFailed`;
5. passes only those selected rows to `SpatialInterpolator.interpolateIDW`
   (`ObservationRepository.kt:858-919`).

`SpatialInterpolator` correctly removes QC-failed rows, but that happens after the repository has
already discarded the same station’s previous usable row. For the deterministic sequence
`station A clean at t1`, `station A QC-failed at t2`, the repository retains only t2 and the
interpolator removes it; station A vanishes instead of contributing its newest usable reading.

The repository then selects `closest`, `newestTimestamp`, and `newestFetchedAt` from the unfiltered
deduplicated list. A QC-failed row can therefore also supply the synthetic blend’s condition or
freshness metadata even when it did not supply the blended temperature.

**Impact**

The synthetic current NWS observation can be missing, can blend too few stations, or can pair a
valid temperature with condition/timestamp metadata from a rejected observation. Existing helper
tests prove that an already-selected QC row is ignored; they do not exercise this repository
selection order.

**Required change**

1. Extract a pure `latestUsableByStation` selector.
2. Exclude QC-failed rows before choosing the newest row per station.
3. Derive interpolation inputs, closest-condition metadata, observed timestamp, and fetched time
   from the same usable set.
4. Keep deterministic ordering by station ID after selection.
5. Retain QC-failed rows in storage and the observations UI; only the computed-current read path
   excludes them.

**Regression criteria**

- A newer QC-failed row does not hide the station’s older in-window usable row.
- An all-QC-failed station contributes nothing.
- Synthetic condition/timestamps come only from usable rows.
- Output is invariant to DAO input ordering.
- Existing KPAO QC UI and shared interpolation tests remain green.

### 3. High — observation identity is not location-safe despite location-scoped reads

**Evidence**

- `ObservationEntity` uses only `(stationId, timestamp)` as its Room primary key.
- Every observation row also stores `locationLat`, `locationLon`, and `distanceKm`, and repository
  reads deliberately scope by location.
- NWS latest and backfill paths build the same station/timestamp row using the current widget/fetch
  location and insert with `OnConflictStrategy.REPLACE`.
- Hourly observation backfill resolves the authoritative location per widget, so different widgets
  can legitimately write the same physical station/timestamp for different stored sites.
- Re-inserting that station/timestamp for site B deterministically replaces site A’s coordinates and
  distance. Site A then loses the row from its location-scoped query.
- `touchLatestFetchedAt(stationId, nowMs)` is global by station ID and can update a row belonging to
  another site, making that site appear freshly fetched even though the attempt was elsewhere.

The July 21 location-fragmentation plan fixed inconsistent coordinate precision and phantom default
locations. It did not change the primary key or site-scope the touch query, so this is a distinct,
still-live identity problem.

**Impact**

Observation history and fetched-at diagnostics can move between locations when multiple widget
locations share a station or a source-level `_MAIN` ID/timestamp. The last writer wins. This
undermines `getObservationsInRange`, `getRecentObservationsNear`, daily recomputation, and current
blend isolation even though all of those APIs present themselves as location-scoped.

**Required change**

1. Change observation identity to
   `(stationId, timestamp, locationLat, locationLon)` using already-quantized write coordinates.
2. Add the required Room migration that creates/copies/replaces the observations table without
   dropping existing data, then recreates the indices.
3. Change `touchLatestFetchedAt` to accept site coordinates and update the newest row for that
   station at that exact quantized site.
4. Audit `getLatestForStation`; retain the global helper only if a real production caller needs
   global semantics, otherwise replace it with a site-scoped query.
5. Keep same-site NWS/Synoptic timestamp collision behavior unchanged: API/web variants at one
   quantized site still resolve onto the same row.

**Regression criteria**

- The same station/timestamp inserted at two quantized sites produces two rows.
- Re-fetching one site replaces only that site’s row.
- A no-data/success touch updates only the target site’s newest station row.
- Location A and B current/daily queries remain isolated after alternating writes.
- A real SQLite migration fixture preserves old rows and permits the new composite identity.

### 4. Medium — `activeSourceList` is passed through the stack but never used

**Evidence**

- `getDailyActualsWithLiveToday` declares `activeSourceList` but does not reference it
  (`ObservationRepository.kt:591-660`).
- `WeatherWidgetWorker` and `WidgetStartupCoordinator` deliberately compute and pass the active
  display sources.
- The May 16 plan that introduced the parameter explicitly required limiting expensive IDW work to
  active sources.
- Current tests pass a single active source but never seed a second inactive source and assert that
  it is excluded.

**Impact**

The method does more observation grouping and time-aligned blending than its contract requests and
returns source maps the caller did not ask for. More importantly, the unused parameter falsely
signals that source filtering is enforced, so future callers can rely on an invariant that does not
exist.

**Required change**

1. Convert `activeSourceList` to a `Set<String>` at the boundary.
2. Filter past daily-history rows, context observations, and hourly fallback inputs to the requested
   sources before aggregation.
3. Return an empty result for an empty active set.
4. If all-source behavior is genuinely needed by another caller, expose it under a separate,
   explicit API instead of making this parameter decorative.

**Regression criteria**

- Seed active NWS and inactive Open-Meteo observations/history/hourly rows; only NWS is returned and
  processed.
- Multiple active sources are retained.
- Empty active sources return an empty map without running the blender.
- Existing daily/high/low/precip consistency tests remain green.

### 5. Structural — the repository must be split into cohesive collaborators

This is an actionable finding because the current shape duplicates the two backfill loops and
scatters cancellation, fallback, mapping, persistence, logging, and recomputation policy across
three fetch paths. The three high-severity findings sit directly on those seams.

The repository directory complexity audit reports:

- `ObservationRepository.kt`: 957 lines (threshold 500).
- `backfillNwsObservationsIfNeeded`: complexity 20, 119 lines.
- `backfillRecentNwsObservations`: complexity 14, 119 lines.
- `fetchStationObservation`: complexity 13, 131 lines.
- `recomputeDailyExtremesForDay`: 150 lines.

**Required target structure**

1. `ObservationRepository` — compatibility facade only.
   - Preserve the current public/internal method surface initially.
   - Delegate in a short, ordered file.
   - Keep the two trivial recent-observation DAO queries here if they remain one-line delegates.
2. `NwsObservationSource`
   - Own grid/station discovery, the station cache, suspend failure/cancellation handling,
     NWS/Synoptic outcome resolution, and NWS API model-to-entity mapping.
   - Return typed results; do not write Room rows or daily history.
3. `NwsCurrentObservationUpdater`
   - Own top-five concurrency, closest-station retry delays, current-row/QC persistence, sparse
     fetch logging, IDW current payload creation, and triggering one affected-day recompute.
4. `NwsObservationBackfiller`
   - Own missing/incomplete-day selection and recent-hour backfill.
   - Use one shared per-station historical fetch routine; parameterize the requested window,
     fallback log tags, stop condition, and result accumulation instead of copying the loop.
5. `DailyActualsStore`
   - Own live-today assembly, active-source filtering, coverage checks, daily recomputation,
     fragment healing, frozen-field preservation, and extrema diagnostics.
6. `CurrentObservationReader`
   - Own the read-time synthetic `NWS_BLEND` construction and its pure latest-usable-by-station
     selector.

`WidgetStateManager(context)` must not be constructed ad hoc inside `DailyActualsStore`. Inject a
small preference interface/provider for the app-wide personal-station weight so the daily component
does not depend on the entire widget state facade.

## Implementation order

### Phase 1 — pin and fix correctness before moving code

1. Add focused cancellation tests around current station catalog and both backfill entry points.
2. Add the repository-level QC-selection regressions and implement the usable-row selector.
3. Add active-source filtering regressions and implement the contract.
4. Add multi-site DAO tests, the composite observation identity migration, and site-scoped touch.
5. Run the focused lane before extraction so behavior changes are independently reviewable.

### Phase 2 — extract source and current-fetch responsibilities

1. Extract `NwsObservationSource`.
2. Compile and run cancellation/source outcome tests.
3. Extract `NwsCurrentObservationUpdater`.
4. Compile and run current-fetch, QC, DAO-touch, and current-view tests.
5. Keep retry delays and fetch-both station-index semantics unchanged.

### Phase 3 — extract and deduplicate backfill

1. Extract `NwsObservationBackfiller`.
2. Replace the duplicated daily/recent station loops with one internal typed station-series
   resolver.
3. Keep workflow-level differences explicit: required-date stopping for daily backfill versus row
   and affected-date accumulation for recent backfill.
4. Re-run cancellation, coverage, fallback, and location-scoping tests.

### Phase 4 — extract daily storage and current reads

1. Extract `DailyActualsStore` without changing time windows, blending algorithms, precipitation
   fallback, fragment healing, or frozen-column copying.
2. Inject the personal-station-weight provider.
3. Extract `CurrentObservationReader`.
4. Reduce `ObservationRepository` to its ordered delegation facade and update Hilt bindings.

### Phase 5 — broad verification

1. Run the repository complexity audit and confirm the facade and collaborators are below the
   current file/function thresholds or document any narrow algorithmic exception.
2. Run formatting/static review checks.
3. Run focused and all duration-bucket unit lanes.
4. Run the broader app/shared/desktop tests affected by shared QC/fallback contracts.
5. Build `assembleDebug`.
6. Install on API 36 and verify actual runtime behavior before declaring the refactor complete.

## Verification matrix

### Focused JVM/Robolectric

At minimum:

```bash
./gradlew :app:testShortDebugUnitTest --tests '*PastDayCoverageTest' \
  --tests '*ObservationDaoTouchTest' \
  --tests '*CurrentTempViewConsistencyTest'

./gradlew :app:testMediumDebugUnitTest --tests '*WeatherRepositoryNwsParallelTest'

./gradlew :app:testLongDebugUnitTest --tests '*ObservationRepositoryDailyMergeTest' \
  --tests '*ObservationRepositoryTodayPrecipTest' \
  --tests '*DailyLiveTodayWindowConsistencyTest' \
  --tests '*YesterdayActualHighConsistencyTest'

./gradlew :shared:testShortShared --tests '*LatestObservationMergeTest' \
  --tests '*ObservationFallbackPolicyTest' \
  --tests '*ActualTemperatureSeriesBuilderTest'
```

Add focused classes for:

- NWS source cancellation and expired-cache fallback.
- Daily/recent backfill cancellation and ordinary-failure behavior.
- QC-safe current observation selection.
- Active-source filtering.
- Multi-site observation identity and touch semantics.
- Real migration coverage for the observation-table primary-key change.

### Broad technical checks

```bash
./scripts/code-review-audit.sh
./gradlew :app:testByDurationDebugUnitTest
./gradlew :shared:testByDurationShared
./gradlew :desktop:testByDurationDesktop
./gradlew assembleDebug
```

### Emulator evidence

Use the running API 36 emulator and preserve/restore its selected source, view, date/offset, and zoom
state.

1. Install the debug build without clearing app data.
2. Exercise an NWS current refresh and an hourly observation backfill.
3. Query `observations` and `app_logs` to prove:
   - stored coordinates are quantized;
   - rows for separate test sites are not replaced or touched across sites;
   - a QC-failed newest row does not suppress an older usable station row in `NWS_BLEND`;
   - cancellation tests did not leave post-cancel inserts or completion breadcrumbs.
4. Capture the widget and Current Observations activity only if the runtime fixtures exercise the
   changed QC/location behavior.
5. Confirm no `CRASH`, `PROC_EXIT`, native-crash, or unexpected worker-cancellation evidence.

## Explicitly preserved behavior

- Top-five station fan-out and closest-station retry delays of 10s then 30s.
- Fetch-both for the nearest configured station tier and metrics-only behavior for the wider tier.
- API wins an exact usable API/web timestamp tie.
- QC-failed rows remain queryable for the station UI and never feed blends.
- `fetchedAt` means the most recent completed attempt for that station at that site.
- Coordinate writes remain quantized through the existing shared choke point.
- Daily actuals use the established context window and time-aligned blend.
- Today comes from the live blend rather than stale `daily_history`.
- Past-day recomputation cutoff remains nine days.
- Daily-history fragment healing and every frozen forecast/noon-cloud field survive Room `REPLACE`.
- High-frequency blend diagnostics stay `VERBOSE`; sparse workflow results remain queryable.

## Completion criteria

Implementation is complete only when:

1. All four correctness findings have regression coverage and are fixed.
2. The six-component target structure is in place and `ObservationRepository` is a small facade.
3. Existing daily/hourly, precipitation, QC, backfill, and location invariants remain green.
4. The observation schema migration is proven with a real SQLite migration test.
5. Focused tests, broad duration lanes, audit, and `assembleDebug` pass.
6. API 36 runtime evidence confirms the affected fetch/store/read behavior.
7. Any device/emulator state changed for verification is restored.

## Implementation outcome

All findings above were implemented after the user explicitly expanded the review-only request to
implementation.

### Structure delivered

1. `ObservationRepository` is now a 166-line compatibility facade.
2. `NwsObservationSource` owns grid/station discovery, cache fallback, NWS/Synoptic resolution, and
   remote-to-entity mapping.
3. `NwsCurrentObservationUpdater` owns concurrent current-station fetches, retries, QC persistence,
   site-scoped touch behavior, IDW output, and affected-day recomputation.
4. `NwsObservationBackfiller` owns daily and recent backfill through one shared station-series
   routine.
5. `DailyActualsStore` owns live-today assembly, active-source filtering, coverage, recomputation,
   fragment healing, frozen columns, and extrema diagnostics.
6. `CurrentObservationReader` owns the synthetic current NWS blend and uses a pure,
   deterministic latest-usable-row selector.
7. `PersonalStationWeightProvider` isolates the app-wide preference from the daily storage
   component.

### Correctness delivered

1. Remote source and backfill boundaries explicitly rethrow `CancellationException`; ordinary
   failures retain their intended fallback behavior, including last-known-good expired station
   cache use.
2. QC filtering now happens before newest-per-station selection, and all synthetic temperature,
   condition, observed-time, and fetched-time fields come from the same usable set.
3. Observation identity is now
   `(stationId, timestamp, locationLat, locationLon)`. Database version 57 includes a data-preserving
   `56 -> 57` migration and recreated indices. Latest-row reads and fetched-at touches are exact-site
   scoped.
4. `activeSourceList` is enforced for history, observation, and hourly inputs; an empty active set
   returns no daily actuals.
5. Raw hourly fallback data is collapsed to the nearest site before it enters daily aggregation,
   and total ordering includes both coordinates where observation identities can otherwise tie.

### Automated verification

1. Focused app and shared tests passed for cancellation propagation, expired-cache fallback,
   QC-safe current selection, active-source filtering, multi-site DAO behavior, site-scoped touch,
   repository compatibility, and deterministic actual-series ordering.
2. `./gradlew test assembleDebug` passed, including 1,744 app tests plus the shared and desktop
   suites and debug APK assembly.
3. `./scripts/code-review-audit.sh app/src/main/java/com/weatherwidget/data/repository` passed all
   four gates. The facade and extracted collaborators are below the 500-line file threshold, with
   no extracted high-complexity or overlong functions reported.
4. `git diff --check` passed.
5. On the API 36 `Medium_Phone_API_36` emulator,
   `WeatherDatabaseMigrationTest` passed all 10 migration tests, including the new multi-site
   observation identity fixture.

### Runtime evidence

1. The migrated live database reports `user_version=57`, retains 8,763 observation rows from the
   pre-refresh snapshot, and exposes the four-column observation primary key.
2. An explicit NWS refresh through the normal Current Observations UI persisted five nearby sites,
   logged API/web freshness decisions, and produced a five-station `NWS_IDW` result.
3. The live backfill path logged `OBS_HOURLY_BACKFILL_SKIP` with `coverage_ok` for widgets 2 and 7,
   proving the extracted backfiller evaluated current persisted coverage without starting an
   unnecessary remote history fetch.
4. Widget 2 received a successful full `WIDGET_PAINT`/`WIDGET_PUSH` in Daily mode after the refresh.
   The final launcher screenshot was visually inspected.
5. No new `CRASH`, `PROC_EXIT`, app-native-crash, or unexpected worker-cancellation evidence was
   observed during the validation window.
6. Widget 2 was restored to the captured state: source `NWS`, view `DAILY`, date offset `-1`, hourly
   offset `0`, and zoom `WIDE`. The emulator remains running.

No commit or push was performed.
