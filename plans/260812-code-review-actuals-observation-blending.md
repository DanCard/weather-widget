# Code Review — Actuals / Observation Blending

Date: 2026-08-12
Scope: `shared/actuals/` + `shared/observations/` (pure logic) and the Android orchestration
(`data/repository/ObservationRepository`, `DailyActualsStore`, `CurrentTempRepository`,
`NwsObservationSource`, `NwsCurrentObservationUpdater`, `NwsObservationBackfiller`,
`NwsApiDailyActualsFetcher`, `CurrentObservationReader`, `DailyHistorySnapshotter`).

## 1. Overall Assessment

This is the **most heavily-documented and hardened subsystem in the codebase**. Almost every
non-obvious line traces to a named historical incident (a measured degree error, a device, a date),
and the "why" comments are load-bearing — they encode the invariant that the next change must not
break. The architecture mirrors the graph engine's winning pattern: pure shared logic
(`ActualTemperatureSeriesBuilder`, `ActualsAggregator`, `StationDailyExtremes`,
`NwsDailyExtremesFetch`, `LatestObservationMerge`, `ObservationFallbackPolicy`,
`ObservationSourceMatcher`, `ObservationOrigin`, `NwsQualityControl`, `HistoricalActualsBackfill`)
plus thin Android wiring.

Standout strengths:

1. **Provenance on the row** — `DailyActualsSource` + `DailyHistoryWriter` (and the
   `actualsSource`/`lastWriter` columns) turn "which of the 5 writers touched this row" from a
   forensic dig into a column read.
2. **Determinism** — the total-order sort in `blendObservationSeries` (timestamp→stationId→lat→lon)
   and the window-independence guarantees are pinned by dedicated tests.
3. **Guard rails** — `poolCoversDay`, `hasRequiredCoverage`, the lone-station skip, synthetic
   deprioritization, and the freeze guards each close a specific real bug.
4. **Centralized constants** — `ObservationOrigin.BLEND_MAX_AGE_MS`, `DAILY_BLEND_CONTEXT_MS`,
   `MAX_WEB_FALLBACK_STATIONS`, etc. are single-sourced so policy and its consumers can't drift.

## 2. Findings

### HIGH

**H1 — `DailyActualsStore.persistExtremes` has a known, acknowledged non-transactional race.**

The KDoc is explicit: the recompute path re-reads the row at the last moment before merge, which
"shrinks the window to the merge loop rather than closing it outright; a genuine fix would wrap
read+merge+write in a transaction, which is awkward while the blend math sits between the caller's
own DAO reads." A concurrent `persistNwsDailyActuals` (the station pull) landing in that window can
have its freshly-written `actualsSource`/`apiHighTemp` provenance silently clobbered by a recompute
whose snapshot predates it — observed on the Pixel 2026-08-08. The freeze guard reads that same
field, so the race also defeats the guard on the cycle that establishes it.

This is the one genuine correctness gap in the subsystem. It is mitigated (last-moment re-read +
freeze guard) but not closed. A full fix needs either (a) the blend math pushed into a `@Transaction`
DAO method, or (b) optimistic concurrency via an `updatedAt`/version compare-and-set. Both are
non-trivial; worth a dedicated plan if the "provenance lost" symptom ever recurs.

### MEDIUM

**M1 — `DailyActualsStore` (673 LOC) is a god class.**

It mixes five responsibilities: live-today aggregation (`getDailyActualsWithLiveToday`), blend
recompute (`recomputeDailyExtremes*`), freeze-guarded persistence (`persistExtremes`), the NWS
station-actual writers (`persistNwsDailyActuals`, `persistCachedStationActuals`,
`findNwsDatesMissingStationActuals`, `stationExtremeFromStoredObservations`), and the Open-Meteo
writer (`persistOpenMeteoPastDayActuals`), plus diagnostics. The station-actual writers belong in
`NwsApiDailyActualsFetcher` (their only caller), and Open-Meteo past-days deserves its own writer.
Same god-class smell we just fixed in the graph engine.

**M2 — Synthetic-station classification is asymmetric.**

Current-temperature fetches for non-NWS sources file the "Current" point under `<SOURCE>_MAIN` (the
historical-backfill synthetic ID) but the N/S/E/W offset points under `<SOURCE>_<index>` (e.g.
`OPEN_WEATHER_MAP_0`). `ObservationSourceMatcher.isSyntheticBackfillStation` matches **only**
`<SOURCE>_MAIN`, so the offset-POI rows are treated as real stations for blend ranking and the
stations UI — despite being the same provider-model value at a shifted coordinate, i.e. equally
synthetic. Also, `getPointsOfInterest`'s 0.072°lat/0.09°lon offsets are undocumented magic numbers.
Worth verifying the intended behavior (are the offset POIs *meant* to rank as stations?), and at
minimum documenting the asymmetry.

**M3 — Two divergent "does this observation belong to this source" rules.**

`ActualTemperatureSeriesBuilder.matchesObservationSource` keys on the stored `api` field
(`obs.api == displaySourceId || api == "Generic"`), while `ObservationSourceMatcher.matchesObservationSource`
keys on stationId prefixes. They answer the same conceptual question with different keys and can
drift — the blend trusts `api`, the UI re-derives from stationId. Not a bug today, but a single
source-of-truth (or an explicit "why two" comment) would remove the trap.

**M4 — `CurrentTempRepository` (618 LOC) is a god class with near-duplicate fetchers.**

It orchestrates the mutex/freshness/throttle loop, dispatches 7 sources, and implements each fetch.
`fetchOpenMeteoCurrent` and `fetchTomorrowIoCurrent` re-implement the `fetchForecastCurrent` template
rather than reusing it (Open-Meteo has a dedicated `getCurrent` endpoint; Tomorrow has 429/401
handling). The offset-POI observation-insertion block is copy-pasted across `fetchForecastCurrent`,
`fetchOpenMeteoCurrent`, and `fetchTomorrowIoCurrent`.

**M5 — `ObservationRepository` has three constructors** (production `@Inject`, a
cohesive-collaborators one, and a legacy test seam that builds the whole graph inline). Documented,
but the legacy seam could be a `@VisibleForTesting` factory rather than a public constructor.

### LOW

**L1 — `NwsObservationSource.stationsForLocation` caches the station list in SharedPreferences keyed
by `stationsUrl.hashCode()`.** A hashCode as a cache key is a latent collision hazard, and prefs is
an odd home for a list that's really persistent data (Room or a file would be cleaner). Low risk
(few URLs).

**L2 — `NwsCurrentObservationUpdater.fetchNwsCurrent` retries only the closest station (inline
10s/30s delays); the other 4 stations get one shot.** Deliberate (closest is most important), but the
delays and the asymmetry are undocumented magic numbers.

**L3 — Four different day/night hour conventions** (`ActualsAggregator` 8–20 for precip,
`StationDailyExtremes` 12–18/0–7 for coverage, `DailyHistorySnapshotter` 20:00/08:00 for freeze
windows, `DAYTIME_COVERAGE_HOUR = 14` for afternoon coverage). Each is justified, but a reader needs
a map of which boundary means what.

**L4 — `blendObservationSeries` reports `dedupSkippedCount = 0` hardcoded** ("thinning removed;
retained as 0 for stat/summary compatibility"). Dead stat field — either drop it or drop the field.

**L5 — One-time `prefs` boolean migration guards** (`PREF_CHANCE_REPAIR_DONE`, etc.) in
`DailyHistorySnapshotter`. The flag is set after the work completes but before any idempotent
verification; clearing prefs (but not the DB) re-runs them. Acceptable, but a `Room`-backed
"migrations applied" table would be more robust.

## 3. Complexity hotspots (for follow-up)

1. **`persistExtremes` merge/freeze logic** — the most delicate write path; owns the
   `actualsSource`/`lastWriter`/freeze invariants. (H1 is here.)
2. **`ActualTemperatureSeriesBuilder.blendObservationSeries`** — the core IDW+decay+synthetic+lone
   blend (~200 LOC, heavily commented). Correct today; the lone-station and synthetic rules are the
   subtle parts.
3. **`CurrentTempRepository` source fan-out** — 7 sources × (mutex + throttle + POI-grid fetch +
   error classification + two log lines each). Highest churn surface.

## 4. Suggested next steps

If you want fixes (phased, like the graph-engine review), I'd start with:
- **Phase 1**: close or further-mitigate H1 (optimistic compare-and-set on `updatedAt`, or move the
  blend into a `@Transaction`).
- **Phase 2**: extract `DailyActualsStore`'s NWS-station-actual + Open-Meteo writers to their owners
  (M1).
- **Phase 3**: document/verify M2's synthetic asymmetry and M3's dual source-matching.
- **Phase 4**: the L1–L5 nits.

Otherwise this is a review-only pass; the subsystem is in much better shape than the label engine
was.
