# Simplification Pass: NwsApi, DesktopWeatherRepository, TemperatureStateResolver

## Context

A codebase-wide complexity scan surfaced three high-value simplification targets. All three are
**behavior-preserving refactors**: no log messages, DB writes, API calls, or rendered output
should change. The goal is to reduce duplication and shorten long functions so the live code is
easier to read and maintain.

This plan is split into **three phases**, each scoped to one module so a failure is contained.
After each phase: run the full unit test suite, pause for user approval, then (on approval) commit
and proceed to the next phase.

### Global invariants (apply to every phase)

1. **No behavior change.** Refactor only — same API requests, same DB rows, same pixels, same log
   tags/levels. If a test goes red, the refactor went wrong; do not "fix" the test.
2. **Preserve diagnostic logging and the dense comment blocks verbatim.** Per AGENTS.md these are
   load-bearing incident forensics; do not trim or relocate them. When a comment is attached to
   code being extracted, the comment travels with the code.
3. **No new abstractions beyond what removes duplication.** No interfaces, no base classes, no
   new modules. Plain private functions only.
4. **Keep diffs review-friendly.** One concern per commit; no drive-by renames outside the scope
   of the listed item.

### Test command (run between every phase)

```bash
./gradlew test          # all unit tests across :app, :shared, :desktop
```

If `:app` widget tests need a device, an optional on-device visual check can be run after Phase 3
only (the refactor is unit-test-covered; instrumented is a belt-and-suspenders final gate).

---

## Phase 1 — NwsApi cleanup (`:shared` only)

Target file: `shared/src/main/kotlin/com/weatherwidget/data/remote/NwsApi.kt`
Existing test coverage: `shared/src/test/kotlin/.../NwsApiTest.kt` (799 lines) and
`app/src/test/java/.../data/remote/NwsApiTest.kt` (799 lines).

### 1a. Extract `parseValidTimeWindow` helper (HIGH)

**Root cause:** the NWS `"start/duration"` validTime format is parsed three times with copy-pasted
logic — `slashIndex` check, `ZonedDateTime.parse` of the start, `Duration.parse` of the tail.

- `parseSkyCoverFromProperties` — `NwsApi.kt:489-496`
- `parseQpfFromProperties` — `NwsApi.kt:515-519`
- `parseDailyExtremes` — `NwsApi.kt:546-549`

**Fix:** add a `private fun parseValidTimeWindow(raw: String): Pair<ZonedDateTime, Duration>?`
in the companion (returns null on any parse failure) and call it from all three sites. The
sky-cover path keeps its own `durationHours` derivation from the `Duration` (it expands per-hour
into a map), so it just swaps the parse block for the helper call.

**Risk:** low — pure extraction, identical outputs.

### 1b. Consolidate HTTP header boilerplate (HIGH)

**Root cause:** 7 call sites each repeat
`header("User-Agent", USER_AGENT); header("Accept", "…")`.

- `NwsApi.kt:224-225, 269-270, 322-325, 344-347, 399-402, 451-456, 600-603`

**Fix:** add a private extension
`private fun HttpRequestBuilder.nwsHeaders(accept: String = "application/json")` that sets both
headers, and replace the repeated `header(...)` lines with `nwsHeaders()` (or `nwsHeaders("application/geo+json")`
where the geo+json variant is used).

**Risk:** low.

### 1c. Drop redundant `else` arm in `parseDailyExtremes` (HIGH)

**Root cause:** the `when (unitCode)` at `NwsApi.kt:560-564` has
`null, "", "wmoUnit:degC" -> (rawValue * 1.8f) + 32f` followed by
`else -> (rawValue * 1.8f) + 32f` — the `else` is an exact duplicate arm, dead as a distinct case.

**Fix:** drop the `else` arm. (Leave the degC conversion as the catch-all for unknown unit
codes, which is the current behavior.)

**Risk:** zero — unreachable-as-different branch removed.

### Phase 1 acceptance

- `./gradlew :shared:test :app:testDebugUnitTest` green (both NwsApi test files live).
- No new public API in `NwsApi`; the helper and extension are `private`.
- Diff is local to `NwsApi.kt`.

**Gate:** pause, present diff summary, await user approval. On approval → commit → Phase 2.

---

## Phase 2 — DesktopWeatherRepository cleanup (`:desktop` only)

Target file: `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt`
Test coverage: `desktop/src/test/.../DesktopUiTest.kt` (985 lines) plus `:desktop` unit tests.

### 2a. Resolve `WeatherSource` once (HIGH)

**Root cause:** the constructor holds `weatherSource: String`, and
`WeatherSource.fromDisplaySource(weatherSource)` is re-invoked 7× across methods
(`DesktopWeatherRepository.kt:75, 143, 173, 222, 231, 431, 700`). Each call re-derives the enum.

**Fix:** keep the `String` constructor param (it is the persistence/config form), but add a
`private val displaySourceEnum: WeatherSource` initialized via `init` and replace the 7 lookups
with the field. Do not change the param shape (callers pass a string).

**Risk:** low — one derivation instead of seven, same value each time.

### 2b. Convert 31 fully-qualified `com.weatherwidget.shared.*` to imports (HIGH, readability)

**Root cause:** 31 inline FQ references like `com.weatherwidget.shared.actuals.ForecastOnlyHistoryPlanner.Candidate`,
`com.weatherwidget.shared.util.DailyHistoryFreeze`, etc.

**Fix:** add the imports at the top of the file, replace FQ references with short names. This is
mechanical; do it as one focused commit so the diff is easy to scan.

**Risk:** zero (compile-equivalent). Do this as its own commit inside Phase 2 so the
behavior-change commits stay clean.

### 2c. Extract `currentTempWindowFor(now)` helper (HIGH)

**Root cause:** `resolveForForecastResult` (`:76-84`) and `resolveDominantContribution` (`:144-148`)
both build the same `nowLocal` / `window` / `zoneId` / `minEpoch` / `maxEpoch` preamble and then
filter observations+hourly to that window.

**Fix:** add a small private data class `private data class CurrentTempWindow(minEpoch, maxEpoch,
zoneId, nowLocal)` and a `private fun currentTempWindowFor(now: Long): CurrentTempWindow` that
calls `CurrentTemperatureResolver.buildCurrentTempResolutionWindow`. Both methods call it and
filter against its bounds.

**Risk:** low — the helper is one line of behavior; the two callers keep their distinct downstream
work (one resolves a temp, the other a contribution).

### 2d. Consolidate `ensureHistory` three branches (HIGH)

**Root cause:** `ensureHistory` (`:313-374`) has three near-identical per-source branches
(WEATHER_API / OPEN_METEO / NWS) that each: check `neededDays <= deepestHistoryDaysFetched`,
acquire `historyFetchMutex`, re-check under the lock, try/catch a fetch, upsert observations,
bump `deepestHistoryDaysFetched`, and emit the same `"ensureHistory deepened to ${neededDays}d back (source=$weatherSource)"` log.

**Fix:** introduce a `private suspend fun runHistoryFetch(neededDays: Int, fetcher: suspend (Int) -> List<…>): Boolean`
that owns the mutex, the double-check, the try/catch, the depth bookkeeping, and the log line.
`ensureHistory` then dispatches: WEATHER_API path stays special (it has the
`ProviderHistoryDecision` cooldown logic) but delegates the actual pull to the helper;
OPEN_METEO and NWS paths become one-liners that pass their fetcher lambda. The
WEATHER_API-specific `backfillWeatherApiHistoryIfNeeded` and `recomputeDailyExtremes` calls
around its branch stay where they are.

**Risk:** medium — this is the most involved extraction. Keep the diff minimal by preserving the
exact log strings and the order of side effects. Tests must stay green untouched.

### 2e. Extract `persistForecastResult` + `runPostFetchBackfills` from `refresh` (MED)

**Root cause:** `refresh` (`:428-554`) is ~125 lines of sequential steps. The first block (persist
the network result: upsert hourly, upsert daily, upsert observations, log BACKFILL_CLOUD) and the
post-fetch block (recompute extremes, fill NWS station actuals, ensure forecast-only rows,
snapshot rain chance, backfill chance/frozen columns, snapshot hourly history, maybe-fetch prior
cloud, ensure climate normals, cleanup) are each cohesive.

**Fix:** extract `private suspend fun persistForecastResult(result: RawFetch, now: Long)` and
`private suspend fun runPostFetchBackfills(now: Long)`. `refresh` becomes:
acquire → fetch forecast+borrowed → `persistForecastResult` → `runPostFetchBackfills` →
hourly-history snapshot → `maybeFetchPriorDayCloudForecast` → `ensureClimateNormals` → cleanup →
log REFRESH → `loadCached ?: rawFetchToSnapshot`. (The cloud fetch, normals, and cleanup stay in
`refresh` because they read as the "tail" of the pipeline; the backfills helper holds only the
data-derivation steps.)

**Risk:** low-medium — pure step-extraction; verify the log tag REFRESH still fires once on
success with the same message.

### 2f. Extract `runOneTimeBackfill` scaffold (MED)

**Root cause:** `backfillForecastChanceSnapshotsIfNeeded` (`:1024-1053`) and
`backfillFrozenDisplayColumnsIfNeeded` (`:1070-1120`) share the same one-time-gate
(`getRecentLogsByTags(...).isNotEmpty() → return`) + same 547-day lookback + same
`getExtremesInRange` + same `upsertDailyHistory` + same marker-log-at-end shape.

**Fix:** add a `private suspend fun runOneTimeBackfill(markerTag: String, planner: (startMs, endMs, now) -> List<DailyHistory>)`
that owns the gate, lookback window, extremes read, upsert, and marker log. The two callers
provide only the per-row `planner` lambda.

**Risk:** low — the two backfills become ~15 lines each.

### Phase 2 acceptance

- `./gradlew :desktop:test` green.
- `./gradlew :shared:test` still green (Phase 1 invariants hold).
- No public API change in `DesktopWeatherRepository`; all new helpers `private`.
- Three commits: (2a+2c+2d+2e+2f behavior-preserving) (2b imports-only) — or one combined
  commit if the user prefers; decided at the gate.

**Gate:** pause, present diff summary, await user approval. On approval → commit → Phase 3.

---

## Phase 3 — TemperatureStateResolver split (`:app` widget path)

Target file: `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
Test coverage: `app/src/test/.../widget/handlers/TemperatureViewHandlerActualsTest.kt` (637),
`widget/handlers/DailyViewHandlerTest.kt` (1844), `TemperatureGraphLabelPlacementRobolectricTest.kt`
(1368), `DailyForecastGraphRendererRoboTest.kt` (1296), plus `WidgetStateManagerTest.kt`.

### 3a. Split `resolve` into phased helpers (HIGH)

**Root cause:** `resolve` (`TemperatureStateResolver.kt:103-540`) is ~440 lines. It already has
numbered phase comments (1 Source Warning, 2 Data Pre-processing, 3 Load Graph Hours, 4 Current
Temp Resolution, 5 Header State Resolution, 6 Graph Rendering). The length makes it hard to see
the pipeline.

**Fix:** extract each numbered phase into a named `private suspend fun` so `resolve` reads as a
short top-level pipeline:
- `resolveSourceWarning(...)` → returns `SourceWarning?` (early-return path stays in `resolve`).
- `loadGraphHoursPhase(...)` → wraps the existing `loadGraphHours` call + the `when` assignment +
  the HOURLY_DAY_EXTREMA log block (comments travel with it).
- `resolveCurrentTempPhase(...)` → the `if (deferCurrentTempResolution)` quick/full branch.
- `buildHeaderState(...)` → the `HeaderState(...)` construction.
- `renderGraphBlock(...)` → the `if (useGraph) { … }` block including the DOMINANT_STATION /
  OBS_POOL_DIAG / actuals-source-label logging and the `TemperatureGraphRenderer.renderGraph`
  call (all diagnostic comments preserved verbatim).
- `assembleResult(...)` → the final `ResolutionResult(...)`.

`resolve` becomes: location/blend resolution → warning check → load graph hours → current temp →
header → graph render → assemble. Each phase is one call.

**Risk:** medium — touches the live widget paint path. The invariant is: identical inputs
produce identical `ResolutionResult`. The diagnostic logging blocks (DOMINANT_STATION,
OBS_POOL_DIAG, TEMP_ACTUALS_DIGEST/DUMP, HOURLY_PAINT_TRACE, HOURLY_DAY_EXTREMA) must fire at the
same moments with the same messages. Preserve every `Log.v` / `appLogDao.log` call and its
payload exactly.

### 3b. Merge `buildWarningResult` + `buildEmptyGraphResult` (HIGH)

**Root cause:** `buildWarningResult` (`:887-920`) and `buildEmptyGraphResult` (`:922-955`) are
~90% identical — both build a 12-field `ResolutionResult` of empties; only `warning` and
`smoothedForecasts` differ.

**Fix:** add `private fun buildFallbackResult(appWidgetId, displaySource, zoom, hourlyOffset, lat,
lon, smoothedForecasts, warning = null): ResolutionResult` and route both callers through it.

**Risk:** low.

### Phase 3 acceptance

- `./gradlew :app:testDebugUnitTest` green (the four large handler/renderer test classes above
  are the primary guard).
- `./gradlew test` green across all modules.
- Optional on-device visual: install on `Medium_Phone_API_36`, take a screenshot of the widget at
  1-row (text) and 2-row (graph) sizes, and confirm the rendered output is unchanged vs. a
  pre-refactor baseline screenshot. This is optional because the refactor is unit-covered, but
  it is the AGENTS.md "verify on device" belt-and-suspenders.

**Gate:** pause, present diff summary, await user approval. On approval → commit → done.

---

## Out of scope (deliberately)

- The dense blend-debug logging / `TEMP_ACTUALS_*` blocks in `TemperatureStateResolver` —
  AGENTS.md says do not be eager to delete debug logging; these comments self-document as
  incident forensics.
- Graph-renderer duplication between `:app` and `:desktop` (TemperatureGraph / DailyForecastGraph
  / CloudCoverGraph) — a much larger cross-module refactor; warrants its own plan if pursued.
- `DailyViewLogic.kt` (856) and `WeatherObservationsActivity.kt` (1182) — flagged for a future
  simplification pass; not touched here to keep this plan reviewable.
- The `WeatherSource` enum / hidden-deprecated sources cleanup — out of scope.
