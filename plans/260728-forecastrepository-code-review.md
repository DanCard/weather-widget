# Code Review: ForecastRepository.kt

Reviewed: 2026-07-28
File: `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` (1420 lines)

## Overall Assessment

`ForecastRepository.kt` should be factored. The repository audit identifies it as the
second-largest Android production file. No individual method currently crosses the audit's
high-complexity threshold; the problem is breadth of responsibility and duplicated persistence
invariants rather than one especially complex method.

The class currently owns:

1. Network-fetch scheduling, throttling, source selection, and parallel orchestration.
2. Provider-result mapping and success/failure bookkeeping.
3. Daily forecast snapshot persistence, deduplication, and history cadence.
4. Hourly live-row persistence and hourly history snapshots.
5. Daily-history freezing and one-time repair/backfill operations.
6. Climate-normal caching.
7. Forecast/cache queries and data retention.

Its 18-parameter constructor is another indicator that these responsibilities should not remain in
one class. The location-fragment findings below also show the practical consequence: an invariant
that is correctly enforced in the hourly persistence path is missing from daily persistence and
daily-history freezing.

## Findings

### F1 — Daily snapshot change gate is not site-exact [HIGH]

`saveForecastSnapshot` (`:1042-1052`) loads existing rows with
`forecastDao.getForecastsInRangeBySource(...)`. That DAO query uses the coarse
`LocationMatch.ROOM_WHERE` proximity box (±0.1°, roughly seven miles), and deliberately returns
history from every coordinate fragment in the box.

The repository then reduces that mixed-site result with:

```kotlin
val latestByDate = existingForecasts.distinctBy { it.targetDate }
    .associateBy { it.targetDate }
```

The subsequent change gate (`:1054-1078`) can therefore compare an incoming row for the current,
quantized write coordinate against a fresher row from a different coordinate fragment. If the
neighboring row already contains the incoming value, `fieldsMatch` suppresses the write even when
the exact display-site row is stale.

This is the daily equivalent of the previously reproduced hourly change-gate regression. The
hourly path already has the correct invariant at `:1205-1213`: query the coarse box, then restrict
the comparison map to the exact quantized coordinate being written with
`siteExactExistingByDateTime`.

**Fix:** Apply an equivalent site-exact filter before constructing `latestByDate`. Compare against
`keyLat`/`keyLon`, not the raw query coordinates. Add a real Room integration test analogous to
`HourlyChangeGateSiteExactIntegrationTest`: seed a stale display-site daily row plus a neighboring
fragment already holding the revised value, save the revised display-site forecast, and assert the
display-site row is updated.

### F2 — Noon cloud is frozen from unfiltered multi-site hourly rows [HIGH]

`snapshotDisplayedRainChance` reads hourly rows through the raw proximity-box query at `:307-312`
and converts every returned coordinate fragment to `HourlyForecast`.

Rain chance handles this correctly: `:335-345` calls
`DailyRainLabels.resolveLiveDayNightChanceAtSite`, supplying the query center so that the
calculation selects the appropriate physical site.

Noon cloud does not. At `:352-357`,
`DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent` receives the entire raw list. That
resolver filters by source and noon timestamp and then uses `firstOrNull`; it performs no location
selection. A stale coordinate fragment can therefore win and be persisted into
`daily_history.noonCloudPercent`. Once its freeze window closes, that incorrect historical value is
permanent.

This is the same stale-fragment mechanism documented in `GraphDataLoader.unifyToNearestSite`,
where a two-day-old noon row previously beat the fresh site and made the daily cloud split flap
between render paths.

**Fix:** Unify the queried `HourlyForecastEntity` rows to the nearest physical site before converting
them, then use the unified list for both rain chance and noon cloud. Alternatively, add a
site-aware noon-cloud resolver, but one shared site selection for all calculations in this method
is less likely to drift.

Add a Room-backed regression test to
`ForecastRepositorySnapshotDisplayedRainChanceTest` with two noon rows for the same source and
date: a stale row inserted first at another coordinate and a fresh row at the display site. Assert
that the fresh site's cloud percentage is frozen.

### F3 — Snapshot dedup range omits the terminal Open-Meteo day [MED]

The existing-row query in `saveForecastSnapshot` ends at today plus 14 days (`:1044`).
`ForecastHorizon.MAX_DAYS` is 16, meaning today through today plus 15 days. Open-Meteo therefore
returns one date that the dedup lookup never sees.

On every fetch, that terminal row is treated as new even when its content is unchanged. This
creates unnecessary forecast-history snapshots and interacts incorrectly with
`getCachedDataBySource`:

1. Unchanged dates inside the lookup range are skipped.
2. The terminal date is inserted with the new `batchFetchedAt`.
3. `getCachedDataBySource` (`:1353-1358`) selects only rows having the maximum exact
   `batchFetchedAt`.
4. The resulting "latest batch" can contain only the terminal day, causing all earlier provider
   dates to be replaced by climate-normal gaps.

`getCachedDataBySource` currently has no production caller beyond the `WeatherRepository`
pass-through, so the display failure is latent. The needless terminal-row history writes are
active.

**Fix:** Derive the existing-row query bounds from `forecastsToSave` (minimum and maximum target
dates), rather than maintaining another forecast-horizon literal. Add a repeated-fetch test with a
complete 16-day Open-Meteo result and assert:

1. The identical second fetch inserts no new snapshots.
2. `getCachedDataBySource` does not reduce the provider batch to the terminal day.

The larger batch-model question should also be made explicit: either persist every row in each
coherent batch, or select the latest row independently per date. Persisting only changed rows while
reading only one exact batch mixes two incompatible models.

### F4 — Best-effort climate-normal warming silently catches cancellation and failures [MED]

`getWeatherData` uses this at `:250`:

```kotlin
runCatching { getHistoricalNormalsByMonthDay(latitude, longitude) }
```

`runCatching` catches `CancellationException` as well as ordinary failures. This can delay or
suppress structured cancellation, depending on where the next cancellation check occurs. It also
drops all diagnostics for an actual climate-normal fetch/cache failure, contrary to the project's
"do not silently swallow exceptions" rule.

**Fix:** Use an explicit `try/catch`:

1. Rethrow `CancellationException`.
2. Log other exceptions once with a sparse diagnostic tag.
3. Continue the main weather fetch after ordinary climate-cache failures.

Audit the similar `runCatching { exception.response.bodyAsText() }` in `logFetchFailure` (`:864`) so
cancellation is not swallowed while formatting an error.

## Factoring Recommendation

Refactor by behavioral ownership, not arbitrary file size.

### 1. Extract `ForecastSnapshotStore`

Move:

1. `mapDailyForecast`
2. `saveForecastSnapshot`
3. Daily snapshot summary formatting
4. The source-specific coherent-batch read currently in `getCachedDataBySource`

This component should own all daily persistence invariants:

1. Coordinate quantization.
2. Site-exact write-side comparison.
3. Forecast horizon/query bounds.
4. Meaningful-change comparison.
5. Snapshot cadence buckets.
6. Coherent-batch semantics.

Do this extraction first because F1 and F3 both live at these boundaries.

### 2. Extract `HourlyForecastStore`

Move:

1. `hasMeaningfulHourlyChange`
2. `siteExactExistingByDateTime`
3. `mergePreservingNullableFields`
4. `saveHourlyEntities`
5. `saveHourlyEntitiesFromShared`
6. `saveHistoricalActuals`

Keep live-row and history-row writes together because they share quantized keys, merge semantics,
and snapshot cadence.

### 3. Extract `DailyHistorySnapshotter`

Move:

1. `snapshotDisplayedRainChance`
2. `freezeDailyHistoryFragment`
3. `repairFrozenRainChanceIfNeeded`
4. `backfillForecastChanceSnapshotsIfNeeded`
5. `backfillFrozenDisplayColumnsIfNeeded`

This isolates freeze-window policy, site selection, and one-time migration preferences from network
orchestration.

### 4. Extract `ForecastFetchCoordinator`

Move:

1. Staleness/source-selection logic.
2. `fetchFromAllApis`
3. `fetchAndSaveSharedForecast`
4. `safeFetch`
5. Fetch failure classification/logging.

The coordinator should return a small fetch outcome while delegating persistence to the two store
components. `ForecastRepository.getWeatherData` can remain the public facade and mutex owner.

### 5. Extract smaller support components

1. `ClimateNormalsRepository` for cache lookup/fetch/persistence.
2. `WeatherRetentionManager` for `cleanOldData`.
3. A query facade only if the remaining public DAO delegates still have real callers.

After extraction, `ForecastRepository` should contain only the externally used orchestration and
query surface.

## Cleanup Opportunities

Confirm and remove during extraction:

1. Unused `freshForecasts` local in `getWeatherData`.
2. Unused `MAX_RETRIES`.
3. Unused `observationRepository` constructor dependency.
4. Apparently dead `saveNwsHourlyForecasts`.
5. `nwsApi` constructor dependency if it is only retained by that dead helper.
6. Unused imports (`LocalDateTime`, `DateTimeFormatter`, `roundToInt`) after compiling.

These should be treated as cleanup accompanying ownership moves, not as a standalone broad rewrite.

## Implementation Order

1. Add failing integration tests for F1, F2, and F3.
2. Fix F1 and F2 without restructuring, proving the location invariants.
3. Fix F3 and define coherent-batch semantics.
4. Fix F4 cancellation/error handling.
5. Extract `ForecastSnapshotStore`.
6. Extract `HourlyForecastStore`.
7. Extract `DailyHistorySnapshotter`.
8. Extract `ForecastFetchCoordinator`.
9. Extract climate/retention support only if the resulting facade remains too broad.
10. Remove confirmed dead dependencies and helpers.

Avoid mixing all extractions into the correctness-fix commit; keeping tests and behavior fixes
reviewable before mechanical moves will make regressions easier to isolate.

## Verification Completed During Review

1. `./gradlew :app:ktlintCheck` — passed.
2. Focused Long repository tests — 14 passed:
   - `ForecastRepositorySnapshotDisplayedRainChanceTest`
   - `ForecastSnapshotDeduplicationTest`
   - `HourlyChangeGateSiteExactIntegrationTest`
3. `./scripts/code-review-audit.sh` — passed:
   - Kotlin size/complexity audit
   - Copy/paste detection
   - KtLint
   - Test-category validation

The existing tests establish a clean baseline but do not cover multi-site daily snapshotting,
multi-site noon-cloud freezing, or an identical second 16-day Open-Meteo batch.

No production code was changed during this review.

## Implementation Status — 2026-07-28

All four review findings were implemented after the review:

1. **F1 complete:** daily snapshot comparison now filters the coarse DAO result to the exact
   quantized write coordinate before applying the meaningful-change gate.
2. **F2 complete:** `DailyNoonCloudCover` now provides a site-aware resolver that selects the
   freshest display-site row per hour; daily-history freezing uses it.
3. **F3 complete:** snapshot lookup bounds now come from the actual incoming batch. Unchanged rows
   retain their existing history identity/content timestamp while their `batchFetchedAt` advances,
   keeping the current source batch coherent without appending unchanged history.
4. **F4 complete:** climate-normal warming explicitly propagates cancellation and persistently logs
   ordinary failures; HTTP error-body extraction also propagates cancellation.

Regression evidence added:

1. A Room-backed daily change-gate test with stale display-site and revised neighboring rows.
2. A Room-backed daily-history freeze test with stale-first raw noon rows from two sites.
3. A repeated identical 16-day batch test asserting sparse history and complete current coverage.
4. Climate-normal warm tests for cancellation propagation and ordinary failure logging.
5. A shared pure test for site-aware noon-cloud selection.

Verification:

1. `./scripts/unit-tests.sh`: 2434 tests passed.
2. `./scripts/code-review-audit.sh`: complexity, CPD, KtLint, and category gates passed.
3. `./gradlew assembleDebug`: passed.
4. Debug APK installed with preserved data on Google Pixel 7 Pro `2A191FDH300PPW` (SDK 37).
5. Live cache refresh completed for widget IDs 78, 79, and 86 with `WIDGET_RENDER_OK`; no fatal,
   render, network-fetch, or climate-warm errors appeared.

## Structural Split Status — 2026-07-28

The behavioral split is now implemented after the correctness fixes were independently verified.
`ForecastRepository.kt` dropped from 1468 lines before extraction to 506 physical lines (a 65%
reduction) and is no longer in the audit's list of production Kotlin files over 500 source lines.

Ownership now maps as follows:

1. `ForecastSnapshotStore.kt` owns daily mapping, snapshot persistence, site-exact change gates,
   cadence buckets, coherent-batch reads, and climate-gap augmentation.
2. `HourlyForecastStore.kt` owns exact-site hourly change detection, nullable-field merging, live
   hourly writes, hourly history, and historical-actual backfill.
3. `DailyHistorySnapshotter.kt` owns freeze-window behavior and the rain/noon-cloud repair and
   backfill preferences.
4. `ForecastFetchCoordinator.kt` owns source selection, parallel provider fetches, result mapping,
   persistence handoff, and failure classification/logging.
5. `ClimateNormalsRepository.kt` owns climate-normal cache lookup, historical fetch, aggregation,
   persistence, and best-effort warming.
6. `WeatherRetentionManager.kt` owns forecast, hourly, observation, daily-history, and diagnostic-log
   retention.
7. `ForecastRepository.kt` remains the public facade and owns only the full-fetch mutex/throttle,
   collaborator wiring, public query surface, and compatibility delegates used by existing callers.

Cleanup completed during extraction:

1. Removed the unused `freshForecasts` local and `MAX_RETRIES`.
2. Removed the dead `saveNwsHourlyForecasts` helper.
3. Removed obsolete imports and moved the raw-hourly-query architecture allowlist entries to the
   two new owners with explicit site-selection justifications.
4. Retained the legacy constructor parameters for source compatibility with existing DI and test
   construction; they are no longer stored as repository state.

Post-split verification:

1. Focused Short, Medium, and Long repository/fetch/persistence tests passed.
2. `./scripts/unit-tests.sh`: 2434 tests passed.
3. `./scripts/code-review-audit.sh`: complexity, CPD, KtLint, and category gates passed.
4. The raw-hourly-proximity allowlist guard initially identified the two moved call sites; its
   ownership entries were updated and the focused guard then passed.
5. `./gradlew assembleDebug` passed; the APK was installed on the running API 36 emulator. An
   explicit UI-only refresh rendered widget IDs 2 and 7 with `WIDGET_RENDER_OK`, the launcher
   screenshot showed the refreshed daily graph, and no render, network, climate, crash, or
   process-exit errors were recorded after the refresh.
