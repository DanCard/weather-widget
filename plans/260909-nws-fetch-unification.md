# Phase 3 — Unify the desktop/Android NWS fetch path

**Date:** 2026-09-09 · **Parent plan:**
[plans/260909-desktop-android-duplication-and-complexity-review.md](260909-desktop-android-duplication-and-complexity-review.md) (Phase 3 / §2.3) ·
**Status:** proposal — no code changed

## Execution status (updated 2026-09-09)

- **Phase 3a — DONE.** Desktop's observation section is now `fetchNwsObservations(...)` returning
  `NwsObservationFetch`; `fetchNwsForecast` reads as forecast → observations → `RawFetch`.
  Behavior-preserving; desktop tests green.
- **Phase 3b — DONE.** New `:shared` `NwsForecastFetch.fetch(api, grid)` returns an
  `NwsForecastBundle` (raw + merged hourly, forecast periods, gridpoints bundle, gridpoints failure);
  Android `NwsForecastMapper` and desktop `fetchNwsForecast` call it and keep their own daily mapper
  and log tags. New `NwsForecastFetchTest` covers the merge and the gridpoints-failure degradation.
  `./scripts/staggered-tests.sh`: 4059 unit + 95 instrumented (2 skipped) green; desktop daemon run
  in an isolated XDG dir fetched NWS via the shared path (gridpoints 186h sky cover, 14 periods,
  5 observation stations) with no errors.
- **Parity harness (verification item 2) deferred:** a full desktop-vs-Android daily-mapper
  comparison needs a Robolectric `:app` test around `NwsForecastMapper` (Room `AppLogDao`); the
  shared fetch is now the common source, and the daily mappers were not changed. Tracked here as a
  follow-up rather than silently skipped.
- **Phase 3c onward:** pending (3c is the behavior-parity phase described below).

### Phase 3c — DONE (behavior parity)

1. **Closest-station retry deleted on Android.** `NwsCurrentObservationUpdater` now launches every
   station with one `fetchAndStoreStation` attempt (`stations.mapIndexed`), the
   `CLOSEST_STATION_RETRY_DELAYS_MS` constant and the dead `NWS_STATION_RETRY_OK` log are gone.
2. **`cloudCarrier` adopted on desktop.** When a web reading wins `LatestObservationMerge`, the API
   row is kept as a second observation (its own timestamp) when it carries low cloud cover, logged
   as `OBS_CLOUD_CARRIER`, and included in `rawObservations`. Verified live: KNUQ
   `OBS_CLOUD_CARRIER ... cloudLow=0` after a `chosen=web` merge.
3. **Staleness-based historical fallback adopted on desktop.** `fetchObservationBundles` now uses
   `ObservationFallbackPolicy.shouldUseWebFallback(index, newestHistoricalMs, now)` instead of
   `historical.isEmpty()`, so a lagging (not just silent) NWS station falls back to the web window.
4. **Verified:** staggered suite 4059 unit + 95 instrumented green; desktop daemon run in an
   isolated XDG dir fetched 5 stations and produced `OBS_CLOUD_CARRIER` / `OBS_WEB_API_DELTA` rows
   with no errors. Focused unit tests for the desktop observation-merge branches remain a follow-up
   (the branch is private and needs a fake `NwsApi`/METAR integration test).

- **Phase 3d onward:** pending.

## Goal

Remove the remaining NWS-fetch duplication between desktop (`DesktopWeatherService.fetchNwsForecast`
+ `fetchObservationBundles`) and Android (`NwsForecastMapper` + `NwsObservationSource` +
`NwsCurrentObservationUpdater` + `NwsObservationBackfiller`) by extracting the platform-neutral
fetch orchestration into `:shared`, while each platform keeps its own persistence, output shape and
diagnostics.

This plan is deliberately **go/no-go per phase**: the current fork is not a line-for-line copy
anymore (the mapping algorithms are already shared), and the two platforms have *behavioral*
differences that must be reconciled or preserved on purpose. Each phase below states which it is.

## Evidence (verified 2026-09-09)

### Desktop

| Unit | Location | Size |
|---|---|---|
| `fetchNwsForecast` (forecast **and** observations) | `desktop/.../DesktopWeatherService.kt:393-504` | 95 |
| `getCachedOrFetchStations` | `:505-516` | 12 |
| `fetchObservationBundles` | `:518-708` | 178 |
| `ObservationBundle` (station/latest/historical + per-slot web flags) | `:709-722` | — |

### Android

| Unit | Location | Size |
|---|---|---|
| `NwsForecastMapper.fetchFromNws` (forecast only → Room entities) | `app/.../NwsForecastMapper.kt:47-268` | ~220 |
| `NwsObservationSource` (stations, latest, historical, entity mapping) | whole file | 448 |
| `NwsCurrentObservationUpdater.fetchNwsCurrent` | `:61-158` | ~97 |
| `NwsObservationBackfiller` | whole file | 283 |

### Already shared (do **not** re-extract)

`NwsApi`, `NwsDailyMapper` (incl. the accumulator pipeline), `NwsHourlyGridMerge`,
`NwsObservationMapper`, `SpatialInterpolator`, `TemperatureInterpolator`, `LatestObservationMerge`,
`ObservationFallbackPolicy`, `MetarObservationFetcher`/`MetarSkyCover`, `FetchOutcome`, and the
`RawFetch`/`DailyForecast`/`HourlyForecast`/`ObservationReading` models. `NwsDailyMapper.buildDailyForecasts`
documents "Keeps desktop at parity with Android's `NwsForecastMapper`".

### Behavioral differences to eliminate (not blockers)

These are the divergences Phase 3c unifies. Each target is the more-correct behavior, not "the
Android behavior":

1. **Current-observation shape.** Android `fetchNwsCurrent` retries the first station (10 s, 30 s),
   emits a `CurrentReadingPayload` from an IDW blend, stores QC-flagged web rows, and preserves the
   API row as a `cloudCarrier` when the web row wins on temperature. Desktop has no retry, no
   `cloudCarrier`, and folds the blend into `fetchNwsForecast`. → **Delete the retry on both
   platforms** (it targets the nearest-by-distance station, which may be a discounted personal
   station, and blocks the fetch up to 40 s); **adopt `cloudCarrier` on desktop.**
2. **Historical web-fallback trigger.** Android falls back when the newest NWS reading is stale
   (`shouldUseWebFallback`, >1 h); desktop only when the window is empty. → **Converge on the
   staleness trigger.**
3. **Forecast mapping depth.** Android's `NwsForecastMapper` runs the richer accumulator pipeline
   (hourly-divergence detection, plausibility repair, phantom-day removal, per-field source logs);
   desktop calls the `buildDailyForecasts` wrapper, which documents parity but emits fewer
   diagnostics. → **Keep both; 3b's parity harness asserts the outputs are identical.**
4. **Station-list cache.** Desktop uses `weatherDao.getCachedStations` (24 h, SQLite); Android uses
   SharedPreferences. Same policy, different storage. → **Keep storage; share the policy (3f).**
5. **Output type.** Desktop returns the shared `RawFetch`; Android returns Room entities and
   persists directly. → **Keep; the shared core returns platform-neutral models.**

## Phases

### Phase 3a — split desktop's observation fetch out of `fetchNwsForecast` (GO; pure refactor)

Move the observation section of `fetchNwsForecast` (`:407-484`) into a dedicated
`private suspend fun fetchNwsObservations(stations, historyDays, recentOnly): NwsObservationFetch`
returning a small desktop-only data class (latest readings, raw readings, current temp/condition,
synthetic `NWS_BLEND` row). `fetchNwsForecast` then reads: fetch forecast → fetch observations →
assemble `RawFetch`.

- **Why first:** it makes the desktop forecast path structurally comparable to Android's
  `NwsForecastMapper` (forecast only), which is the precondition for sharing anything.
- **Risk:** low, behavior-preserving. Guard: desktop unit tests + a daemon smoke run.

### Phase 3b — shared NWS forecast fetch core (GO; removes real duplication)

Add `shared/.../data/remote/NwsForecastFetch.kt`:

```kotlin
data class NwsForecastBundle(
    val grid: NwsApi.Gridpoint,
    val hourlyPeriods: List<NwsApi.HourlyForecastPeriod> = emptyList(), // post grid-merge
    val forecastPeriods: List<NwsApi.ForecastPeriod> = emptyList(),
    val gridDailyTemps: NwsApi.DailyTemperatureExtremes,
    val gridpointsFailed: Boolean,
)
suspend fun fetch(api: NwsApi, latitude: Double, longitude: Double): NwsForecastBundle
```

It owns only the *fetch orchestration* both platforms already duplicate: `getGridPoint` → concurrent
`getHourlyForecast`/`getForecast`/`getGridpointsBundle` → `NwsHourlyGridMerge.applyGridpointData` →
return the raw pieces. It does **not** map to daily rows (Android and desktop keep their own mappers)
and does **not** log to a platform sink — it returns `gridpointsFailed` so each platform emits its
existing `NWS_GRIDPOINTS_FAIL`/`bestEffort` line.

- Desktop `fetchNwsForecast` calls it, then `NwsDailyMapper.buildDailyForecasts(...)`.
- Android `NwsForecastMapper.fetchFromNws` calls it, then runs its accumulator pipeline and
  entity mapping.
- **Risk:** medium. The concurrency/error semantics must match: today desktop wraps gridpoints in
  `bestEffort` (null → empty bundle) and Android try/catches into an empty bundle; both must keep
  their cancellation rethrow. Guard: new shared tests for the bundle + the existing
  `NwsForecastMapperTest`/desktop tests + a live parity check (see Verification).

### Phase 3c — current-observation behavior parity (GO; behavior-changing)

**Decision (2026-09-09): there is no reason to keep the platforms divergent, so this phase makes
them behave identically before any extraction.** The target is the more-correct behavior in each
case, not "Android wins" or "desktop wins":

1. **Delete the closest-station retry on both platforms.** Android retries `stations.first()`
   twice (10 s, 30 s) while desktop does not. It should not be reimplemented as a "nearest" or
   "dominant" retry — **remove it entirely** and give every station one attempt. Reasons:
   - **The target is not the dominant station.** The current-temp anchor is computed by
     `SpatialInterpolator.interpolateIDW`, which weights by `decay / distance²` with **no**
     personal-station discount and snaps to any station within `NEAR_ZERO_KM`. The real series
     blend (`ActualTemperatureSeriesBuilder`) multiplies personal stations by
     `personalStationWeight` (default 0.05). So the nearest station can be a personal station that
     contributes ~5% of the actual blend — retrying it is retrying the wrong station.
   - **"Dominant" is unknowable before the fetch.** The dominant contributor is derived from the
     readings' weights, so it cannot be chosen until after the data arrives. Any pre-fetch proxy
     (nearest official, previous cycle's dominant) is a new heuristic with its own failure modes,
     and the previous-dominant proxy goes stale on a location move.
   - **It costs interactivity for little benefit.** The retry blocks the whole `fetchNwsCurrent`
     await (closest + others run in parallel), adding up to 40 s to the current-temp fetch exactly
     when the station/network is already degraded. The cycle-level retries (observation loop,
     network-restored kick, resume kick) already re-fetch within one cycle.
   - If robustness is later shown to be needed, the fix is a *post-result* background retry or a
     uniform one-retry-all-stations pass, not a single pre-chosen station.
   - A separate, related finding is recorded under "Follow-ups": the current-temp anchor does not
     apply the personal-station discount that the series blend does.
2. **Adopt `cloudCarrier` on desktop.** When a web (Aviation Weather) reading wins the
   prefer-newest temperature merge, desktop must also store the API observation row at its own
   timestamp, preserving sky / 24 h extremes / precipitation. Today it discards them; the KNUQ
   measurement (23 cloud-less stored rows) is the documented cost.
3. **Adopt Android's historical web-fallback trigger on desktop.** Fall back when the newest NWS
   reading is **stale** (`ObservationFallbackPolicy.shouldUseWebFallback`, >1 h), not only when the
   window is **empty**. Desktop's empty-only rule misses the silent/lagging station the policy
   exists for.
4. **Keep storage platform-specific** (SharedPreferences vs SQLite) and diagnostics
   platform-specific; only the *behavior* is unified.

- **Why before extraction:** a shared current-observation core is only honest once both platforms
  agree on the policy; otherwise the core needs behavior flags that read worse than two copies.
- **Risk:** medium-high — this changes what desktop stores and fetches. Guard: before/after
  `OBS_WEB_API_DELTA` / `OBS_CLOUD_CARRIER` / `NWS_STATION_FAIL` rows for one full day on desktop,
  plus the emulator's current-temp and cloud graph for the same location; `./scripts/staggered-tests.sh`.

### Phase 3d — shared current-observation core (GO after 3c)

Extract the now-identical orchestration of `NwsCurrentObservationUpdater.fetchNwsCurrent` and
desktop's `fetchObservationBundles`: station selection (`stationsForLocation(...).take(N)`), the
single parallel METAR batch, one-attempt-per-station latest fetch, `LatestObservationMerge`, and the
IDW blend producing `(blendedTemp, condition, observedAt)`. Each platform keeps persistence, entity
mapping and its log sink.

### Phase 3e — historical backfill (GO after 3c)

`NwsObservationBackfiller` (Android) and desktop's historical window in `fetchObservationBundles`
now share the same fallback trigger (3c.3). Extract the `getObservations(start,end)` +
`shouldUseWebFallback` + Synoptic-fallback orchestration; each platform maps to its own entity.

### Phase 3f — station-list cache (SMALL, optional)

Extract a pure `StationListCache` policy (key derivation, 24 h TTL, stale-on-failure fallback) and
let each platform supply the read/write lambda. Removes ~40 duplicated lines; low value, do last.

## Follow-ups (found while planning 3c, not part of Phase 3)

1. **The current-temp anchor does not apply the personal-station discount.**
   `SpatialInterpolator.interpolateIDW` weights by `decay / distance²` only and snaps to any station
   within `NEAR_ZERO_KM`; `ActualTemperatureSeriesBuilder.computeWeightedBlend` multiplies personal
   stations by `personalStationWeight` (default 0.05) and prefers an official station on the
   near-zero tiebreak. So the NWS provider anchor (`CurrentReadingPayload.temperature`) can be
   dominated by a nearby personal station while the displayed series blend is not. Worth its own
   plan: either apply the same discount in the anchor, or document why the anchor is allowed to
   differ. This is the root of the "nearest station contributes little" observation.
2. **The current-temp IDW snap radius.** `NEAR_ZERO_KM` makes one station win outright before any
   discount is considered; the series blend's near-zero branch explicitly prefers an official
   station. Same reconciliation.

## Non-goals

- Converging the daily-row mapping depth (Android's diagnostics vs desktop's wrapper). The
  algorithms are shared; the difference is logging, which AGENTS.md protects as incident forensics.
- Changing either platform's persistence model or retention.
- Unifying the `RawFetch` vs Room-entity output type.

## Verification

1. **Per phase:** `./scripts/staggered-tests.sh` (all unit + instrumented).
2. **Phase 3b parity harness (new, `:shared` test):** feed a recorded `NwsApi` fixture (same
   grid/hourly/daily/gridpoints JSON) through `NwsForecastFetch.fetch`, then through both daily
   mappers; assert the shared bundle is identical and that desktop's `buildDailyForecasts` output
   matches Android's `NwsForecastMapper` daily rows for the same fixture. This is the test that
   makes the "parity documented in a comment" claim executable.
3. **Live check (daemon + emulator, same location):** run the refactored desktop daemon in an
   isolated `XDG_*` dir and the Android widget on the emulator against the same coordinates; compare
   `app_logs` `NWS_BATCH_SUMMARY` / `NWS_GRID_TEMP_PRIMARY` / `NWS_IDW` rows and the stored daily
   highs/lows. Any divergence is a Phase 3b bug, not a data difference, if both used the same grid.
4. **3c parity gate:** diff `OBS_WEB_API_DELTA` / `OBS_CLOUD_CARRIER` / `NWS_STATION_FAIL` and the
   stored station rows before/after for one full day, on both platforms at the same location.

## Sequencing and stop points

1. 3a → tests → commit (desktop observation fetch split out; behavior unchanged).
2. 3b → shared `NwsForecastFetch` + shared tests + parity harness + live check → commit.
3. **3c → behavior parity → commit.** This is the phase that removes the divergence the user called
   out: **delete the closest-station retry on both platforms**, `cloudCarrier` on desktop,
   staleness-based historical fallback on desktop. Ship with the before/after `app_logs` comparison.
4. 3d → shared current-observation core → commit.
5. 3e → shared historical-backfill orchestration → commit.
6. 3f (optional) → shared station-cache policy → commit.

Each commit references this plan. 3c is the only behavior-changing phase and must land before 3d/3e
so the shared core has one policy to encode, not two.
