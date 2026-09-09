# Desktop/Android Duplication & Complexity Review — 2026-09-09

Scope: `app/src/main` (Android), `desktop/src/main` (Compose Desktop), `shared/src/main`.
Method: read the cited code, cross-module function-name intersection, exact 8+-line block matching
across `:app` and `:desktop`, and brace-accurate function/block length measurement. Nothing below is
inferred from file size alone. No code was changed to produce this plan.

The prior audit (`plans/260908-codebase-quality-audit.md`) is **largely landed** — see §5. This plan
covers what is *still* duplicated or too long after those refactors. The centre of gravity has moved
from the Android widget handlers to the **desktop daemon/repository layer**.

---

## 1. Recommended next step (decision)

**Phase 1 — extract the shared daily-history / actuals maintenance orchestration into `:shared`
behind a narrow DAO port, and delete the desktop twins.**

Why this one first:

1. It is the largest remaining cross-platform duplication: ~**513 desktop lines** in
   `DesktopWeatherRepository.kt` mirroring ~**1,000 Android lines** across five repository classes.
2. The *algorithms* are already single-sourced in `:shared`; only the orchestration/DAO glue is
   forked. The extraction is therefore seam plumbing, not re-implementing weather logic.
3. The fork has **already diverged in observable ways** (§2.2), so this is a live correctness issue,
   not a cosmetic one — exactly the failure mode "desktop and Android must be shared, not
   duplicated" is meant to prevent.
4. It is the hot path the last several firefighting commits touched (stale daily rain / fragment
   repair), so removing the fork reduces future bug surface.

Do **not** start with the desktop graph composables (Phase 2) even though they are longer: they are
platform-idiomatic Compose drawing with shared geometry already, and they have no duplication
divergence. Structural length is a readability cost; the Phase 1 fork is a correctness cost.

### Execution status (updated 2026-09-09)

- **Phase 1a — daily-history maintenance: DONE.** New `:shared`
  `shared/.../actuals/DailyHistoryMaintenance.kt` (+ `DailyHistoryMaintenanceTest`) now plans all four
  passes; `DailyHistorySnapshotter` (Android) and `DesktopWeatherRepository` (desktop) are thin
  adapters. `./scripts/staggered-tests.sh`: **4053 unit + 95 instrumented (2 skipped), all green.**
- **Implementation deviation from this plan:** the seam is a set of *pure/suspend planner functions*
  plus a platform DAO lambda, not a DAO port interface. This follows the existing
  `ForecastOnlyHistoryPlanner` "pure planner + platform writer" convention and avoids a new
  abstraction, per the repo's simplification invariants. The DAO reads/writes, one-time gate
  (Android prefs / desktop `app_logs` marker) and log sinks stay platform-specific.
- **Divergences fixed by 1a (all now match Android's richer behaviour):**
  1. Frozen-display backfill now rejects degenerate `high == low` overlays on both platforms
     (desktop already did; Android now does — the row is a placeholder, not a forecast).
  2. Both platforms stamp `lastWriter = FORECAST_FREEZE` on the live snapshot and backfills
     (desktop was missing it on the live snapshot).
  3. Desktop chance backfill now stitches hourly history (`HourlyForecastStitcher`) like Android.
  4. Desktop frozen-display backfill now reads `getForecastsInRangeBySource` (so climate-normal and
     implausible-temp rows are filtered like Android's `getAllForecastsInRange`).
  5. Desktop now persists `FREEZE_RAIN_CHANCE` to `app_logs` (was an ephemeral `Log.d`).
  6. Fixed a pre-existing desktop arg-order bug: `weatherDao.log("INFO", "FORECAST_ONLY_HISTORY", …)`
     wrote tag=`INFO`/level=`created=…`; now `log("FORECAST_ONLY_HISTORY", …, "INFO")`.
- **Phase 1b — NWS station actuals: DONE.** New `:shared`
  `shared/.../actuals/NwsStationActualsMaintenance.kt` (+ test) owns the pulled/cached/
  insufficient/unavailable split; `NwsApiDailyActualsFetcher` (Android) and
  `DesktopWeatherRepository.fillNwsStationActualsIfNeeded` are now adapters over the shared
  `NwsDailyExtremesFetch` core. Fixes the desktop outcome-log payload divergence
  (`insufficientUnresolved` now reported, matching Android).
- **Phase 1c — Weather-API history backfill: deliberately NOT extracted.** `ProviderHistoryPolicy`
  and `retryAtFromMessage` are already the single-sourced core. What remains is ~90 lines of
  platform glue that fetches *different payload shapes* (Android filters `history.hourly`; desktop
  filters `RawFetch.rawObservations`) and persists through different stores. A shared abstraction
  would add indirection without removing meaningful duplication, so the platforms keep their thin
  adapters. Revisit only if the fetch payloads converge.
- **Phase 1 complete. `./scripts/staggered-tests.sh`: 4057 unit + 95 instrumented (2 skipped), all green.**

- **Phase 2 — desktop daemon/UI decomposition: DONE (daemon) / partial (UI).**
  `DaemonProcess.kt` went from 962 to 107 lines: the daemon's state, fetch loops, kick handlers and
  watchers moved verbatim into a new `DaemonRuntime` class (`desktop/.../DaemonRuntime.kt`), and
  `runDaemon()` is now a composition root that builds the DB/state flows and calls `runtime.start()`.
  From `DesktopUiApplication.kt`, the self-healing `.ui-show`/`.data-updated` WatchService effect was
  extracted to a `DataUpdateWatcher` composable (the window hosts were already separate composables;
  the remaining composition root is declarative state/effect wiring, deferred as lower value/riskier
  to split further). Verified by running the refactored daemon in an isolated XDG dir for 25s
  (full fetch, METAR, backfills, panel IPC) and the UI for 40s; `./scripts/staggered-tests.sh` again
  4057 unit + 95 instrumented green, `:desktop:createDistributable` passes.

---

## 2. Duplicate code still present

### 2.1 The desktop repository twins (HIGH — target of Phase 1)

`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt` (1,431 lines) is a
single class that absorbed the desktop twins of five Android repository classes. Every algorithm
below the seam (`ForecastOnlyHistoryPlanner`, `DailyHistoryFreeze`, `DailyRainLabels`,
`DailyNoonCloudCover`, `NwsDailyExtremesFetch`, `HourlyForecastStitcher`, `NwsDailyMapper`,
`NwsHourlyGridMerge`) is already in `:shared`; the forked part is window iteration, DAO reads,
entity/row mapping, upsert, and logging.

| Desktop method (lines) | Android twin | Android size |
|---|---|---|
| `ensureForecastOnlyHistoryRows` :906-963 (57) | `DailyHistorySnapshotter.ensureForecastOnlyHistoryRows` :58-133 | 76 |
| `snapshotDisplayedRainChance` :964-1091 (114) | `DailyHistorySnapshotter.snapshotDisplayedRainChance` + `freezeDailyHistoryFragment` :134-298 | 165 |
| `backfillForecastChanceSnapshotsIfNeeded` :1092-1133 (26) | `DailyHistorySnapshotter.backfillForecastChanceSnapshotsIfNeeded` :371-428 | 58 |
| `backfillFrozenDisplayColumnsIfNeeded` :1134-1188 (47) | `DailyHistorySnapshotter.backfillFrozenDisplayColumnsIfNeeded` :429-510 | 82 |
| `fillNwsStationActualsIfNeeded` :1303-1430 (127) | `NwsApiDailyActualsFetcher` (155) + `NwsStationActualsStore` (212) | 367 |
| `backfillWeatherApiHistoryIfNeeded` :652-743 (91) | `WeatherApiHistoryBackfiller` | 222 |
| `recomputeDailyExtremes` :823-874 (51) | `DailyActualsStore` (relevant part) | — |

The exact-block matcher confirms the shape: of the 9 cross-module ≥8-line verbatim blocks in the
whole codebase, **6 are in `DesktopWeatherRepository.kt`**, all against Android repository files
(`DailyHistorySnapshotter:179↔1004`, `DailyHistorySnapshotter:246↔1035`, `NwsStationActualsStore:107↔1389`,
`NwsStationActualsStore:189↔1404`, `WeatherApiHistoryBackfiller:64↔624`,
`NwsApiDailyActualsFetcher:61↔1325`).

### 2.2 Divergences already caused by the fork (these are bugs, not style)

1. **Degenerate-day filter differs.** Android's frozen-display backfill keeps any row with
   `!isClimateNormal && highTemp != null && lowTemp != null`
   (`DailyHistorySnapshotter.kt:474-480`). Desktop additionally requires `highTemp != it.lowTemp`
   (`DesktopWeatherRepository.kt:1158-1160`), so desktop rejects collapsed high==low rows and
   Android does not. Same DB, same day, two answers.
2. **`lastWriter` is not set on desktop.** Android stamps `DailyHistoryWriter.FORECAST_FREEZE` on
   both the live snapshot (`DailyHistorySnapshotter.kt:409`) and the frozen-display backfill
   (`:497`). Desktop's live snapshot copy (`DesktopWeatherRepository.kt:1060-1067`) and chance
   backfill (`:1123`) omit it, so desktop rows keep a stale `lastWriter` — the field that exists
   specifically to answer "which writer touched this row".
3. **Stitching differs.** Android chance backfill runs `HourlyForecastStitcher.stitch(...)` before
   `calculateDayNightPrecipProbabilities` (`DailyHistorySnapshotter.kt:396-403`); desktop passes
   `weatherDao.getHourlyHistory(...)` straight through (`DesktopWeatherRepository.kt:1104`).
4. **Outcome log payloads differ.** Android `NWS_STATION_ACTUALS_OUTCOME` reports
   `insufficientUnresolved=…` (`NwsApiDailyActualsFetcher.kt:117-124`); desktop reports
   `unavailable=…` (`DesktopWeatherRepository.kt:1420-1425`). The two installs cannot be compared
   from `app_logs`.
5. **Forecast read window differs.** Android reads forecast rows to `endMs + 1 day`
   (`DailyHistorySnapshotter.kt:67-73`); desktop reads to `todayMs` (`DesktopWeatherRepository.kt:911`).
   Currently benign because the planner drops today, but it is unguarded drift.

### 2.3 Desktop NWS fetch orchestration (HIGH — Phase 3)

`desktop/.../DesktopWeatherService.kt` re-implements the NWS fetch pipeline that Android splits
across `NwsForecastMapper.kt` (376), `NwsObservationSource.kt` (448), and
`NwsCurrentObservationUpdater.kt` (286):

- `fetchNwsForecast` :393-504 (95 lines) + `fetchObservationBundles` :518-708 (178 lines)
- Android: `ForecastFetchCoordinator.fetchFromNws` :223-235 delegates to `NwsForecastMapper.fetchFromNws`.

Both already use the shared `NwsHourlyGridMerge`, `NwsDailyMapper`, `NwsObservationMapper`,
`SpatialInterpolator`, `TemperatureInterpolator`, and the shared `RawFetch` model
(`shared/.../data/model/ForecastTypes.kt:169`). The comments in the desktop copy say "Matches
Android parity" in two places — a standing drift invitation. This is the second-biggest duplication
locus and the natural follow-on once the Phase 1 port pattern exists.

### 2.4 Smaller remaining forks (LOW — mechanical)

1. `formatAge`/age rendering is now mostly consolidated in `AgeFormatter`
   (`shared/.../util/WeatherTimeUtils.kt:16`), but three hand-rolled copies remain:
   `StaleObservationFallback.formatAge` (`:55`), `BlendTableFormatter.formatAgeMs` (`:80`), and the
   two `formatAgeLabel` wrappers (`app/.../TemperatureGraphStyle.kt:76`,
   `desktop/.../TemperatureGraph.kt:113`). Pick one style enum and route them through `AgeFormatter`.
2. `TempUtils.display`/`displayDelta` exist (`shared/.../util/TempUtils.kt:11-14`) but 29 inline
   `if (useCelsius)` conversions remain, concentrated in `desktop/.../StatisticsWindow.kt` (7) and
   `desktop/.../ForecastHistoryWindow.kt` (3).
3. `private fun Float.dp(density)` still declared 8× in `:app` (`DailyGraphPaintCache.kt`,
   `DailyHighLabelPlanner.kt`, `DailyColumnRenderer.kt`, `DailyForecastGraphRenderer.kt`,
   `DailyGraphLayoutResolver.kt`, `DailyForecastHeaderRenderer.kt`, `TodayColumnOverlayRenderer.kt`,
   `DailyBarRenderer.kt`). One `internal object Dp` collapses them.
4. `WeatherSource` metadata still has a parallel string→enum map: `sourceDescription` in
   `app/.../ui/SettingsActivity.kt:307-314` (with an `else -> ""`) mirrors the enum's own
   description fields; descriptions that do not need string-resource localization should live on
   the enum.

### 2.5 Confirmed already shared — do not re-do

Icon/condition mapping, colors, zoom stage/rules, temperature interpolation and current-temp
resolution, unit conversion primitives, header precipitation, hour-data assembly, sun position,
rain analysis, accuracy stats (`AccuracyPure` + `DesktopAccuracyCalculator` live in
`shared/.../stats/`), graph geometry/label engines (52 files in `shared/.../shared/graph/`),
`ViewMode` (desktop is a `typealias` to the shared enum), `RawFetch`/`ForecastSnapshot`, and the
source-metadata/registry consolidation. `shared/.../data/local/desktop/DesktopWeatherDao.kt`
(1,563) is **not** a fork — it is a JDBC implementation, not a Room DAO.

---

## 3. Too long (god functions / god blocks)

Now that the Android handlers were split, the largest single units are on the desktop side.
Brace-accurate block sizes (not file sizes):

| Lines | Unit | Location |
|---|---|---|
| 930 | `Canvas { … }` draw lambda inside `TemperatureGraph` | `desktop/.../TemperatureGraph.kt:180-1110` |
| 914 | `runDaemon()` — contains nested `quit`, `checkDominantTempWatch`, `startFetchLoops` (257), `kickResumeRefresh`, `kickNetworkRestoredRefresh`, `kickObservationCatchUp` | `desktop/.../DaemonProcess.kt:47-961` |
| 690 | `application { … }` body of `runDesktopUiApplication()` — DB, DAO, repository, service, tray, every window host | `desktop/.../DesktopUiApplication.kt:87-777` |
| 638 | `Canvas { … }` draw lambda inside `DailyForecastGraph` | `desktop/.../DailyForecastGraph.kt:69-707` |
| 477 | `Canvas { … }` draw lambda inside `CloudCoverGraph` | `desktop/.../CloudCoverGraph.kt:101-578` |
| 450 | `WidgetPopup` body | `desktop/.../DesktopWidgetPopup.kt:57-507` |
| 491 | `CloudCoverGraphRenderer.renderGraph` | `app/.../widget/CloudCoverGraphRenderer.kt:172` |
| 432 | `DailyViewHandler.updateWidget` | `app/.../widget/handlers/DailyViewHandler.kt:130` |
| 415 | `PrecipViewHandler.updateWidget` | `app/.../widget/handlers/PrecipViewHandler.kt:52` |
| 352 | `DailyGraphRenderer.render` | `app/.../widget/handlers/DailyGraphRenderer.kt:46` |
| 350 | `TemperatureStateResolver.resolve` | `app/.../widget/handlers/TemperatureStateResolver.kt:105` |
| 275 | `DailyViewLogic.prepareGraphDayInputs` | `app/.../widget/handlers/DailyViewLogic.kt:384` |
| 274 | `PrecipitationGraphRenderer.calculateLayout` | `app/.../widget/PrecipitationGraphRenderer.kt:171` |
| 254 | `DailyHeaderResolver.resolveState` | `app/.../widget/handlers/DailyHeaderResolver.kt:108` |

`runDaemon` (914) and `runDesktopUiApplication` (690) are the two that matter most: they are
process-lifetime orchestration with implicit ownership rules, previously flagged as M4/L2 in
`plans/260813-code-review-desktop-architecture.md` but never decomposed. The three desktop `Canvas`
lambdas (930/638/477) are long but structurally coherent — they are the platform's drawing pass over
already-shared geometry; splitting them is a readability win, not a duplication fix.

---

## 4. Complicated / risky structure

1. **`DesktopWeatherRepository` (1,431 lines, 26 methods)** — the class behind §2.1. After Phase 1
   it should shrink by ~500 lines; the remaining load/fetch/resolve methods are cohesive.
2. **`WeatherDatabase.kt` (854 lines)** — unchanged since the 260908 audit: `version = 70` with **26**
   hand-written migrations (~600 lines), `exportSchema = true` with auto-migration unused, and
   `.fallbackToDestructiveMigration(dropAllTables = true)` as the gap behaviour. Not a duplication
   issue, but the highest-blast-radius file in the repo; needs its own plan and an instrumented
   migration test, not a drive-by.
3. **Global mutable state on the interaction path** — `WidgetPushDispatcher` (process-lifetime
   collections), `BlendSeriesCache` (global MRU), `WeatherDatabase` `@Volatile` test statics,
   `ForecastFetchCoordinator.lastPriorCloudFetchMs`, `DailyActualsStore` signature cache. Flagged in
   260908 D3; still present. Low priority relative to Phase 1.
4. **Daemon ownership is implicit** — `runDaemon`'s nested functions and `var x: Job?` fields encode
   ownership in ordering rather than types (260813 H1/M4). Phase 2 addresses the *shape*; the
   single-writer redesign is a larger, separate change.

---

## 5. Status of the 260908 audit (verified today)

Landed — do not re-do:
- C1 Visual Crossing deletion (commit `33828174`); C3 dead symbols deleted.
- C2 source metadata consolidated: `requiresUserKey` now derives from `requiresApiKey`
  (`shared/.../shared/util/ApiKeySignupUrls.kt:18`), so the SILURIAN contradiction is gone.
- A1 `RainAmountLabelPlacer`, A2 `CloudLayerGlyphPlan`, A4 `ForecastHistoryViewLogic.formatBias`
  (both app copies now delegate), A6 `AgeFormatter`, A8 `HttpResponse.require2xx`, A9 `labelFor`/
  `formatCoord`/`formatPersonalStationDiscount` — all single-sourced.
- B1/B2/B3/B4/B5/B7/B8/B9/B10/B11 splits all landed (`CloudCoverGraphRenderer` 939→784,
  `CloudCoverViewHandler` 919→786, `WidgetRenderer` 683→546, `DailyViewLogic` 856→726,
  `WeatherObservationsActivity` 1182→937, `WidgetActionReceiver` → 358, plus extracted files).

Still open from 260908:
- **A3** (the fork in §2.1) — the item this plan targets.
- A5/A7 mechanical leftovers (§2.4).
- D2 migrations (§4.2).
- D3 global state (§4.3).
- C4 doc/code mismatch: `AGENTS.md` still says Tomorrow.io is "debug-only", but
  `WeatherSourceOrdering.ALL_CONFIGURABLE` includes it (`WeatherSourceOrdering.kt:30`) and
  `AppModule` always provides the API, so it is selectable in release on both platforms. Either the
  doc or the code must change.

---

## 6. Phasing

Each phase is behavior-preserving except the explicit divergence fixes, ends with
`./gradlew test` (all modules), and pauses for approval before the next.

### Phase 1 — Shared daily-history / actuals maintenance (recommended next step)
1. Define a narrow port in `:shared` (e.g. `shared/.../actuals/DailyHistoryPort`) covering only the
   DAO/log operations the orchestration needs: read extremes-in-range, upsert daily history, read
   forecasts-in-range, read hourly history, read daily snapshots, log, one-time gate. Implement it
   once per platform (Room DAO adapter in `:app`, JDBC adapter in `:desktop`).
2. Move the orchestration for the four `DailyHistorySnapshotter` paths into a shared
   `DailyHistoryMaintenance`; fix the §2.2 divergences to the Android behaviour (it is the richer
   one: `lastWriter`, stitching) and reconcile the degenerate-day filter with an explicit decision
   recorded in the plan.
3. Do the same for the NWS station-actuals orchestrator (`NwsStationActualsMaintenance`) and the
   Weather-API history backfill.
4. Delete the desktop twins; `DesktopWeatherRepository` calls the shared orchestrators.
5. Tests: port the existing Android `DailyHistorySnapshotter` behaviour tests to `:shared` against a
   fake port; keep the desktop DB-backed tests as the integration guard.

### Phase 2 — Desktop daemon/UI decomposition (structural)
1. Split `runDaemon` into `FetchScheduler`, `CurrentStatusResolver` wiring, monitor setup, and
   `PanelPublisher` wiring (260813 M4). Preserve the suspend/resume and network-restored logic
   verbatim.
2. Split `runDesktopUiApplication` into an ordered composition root plus per-window/per-service
   composables (260813 L2).
3. Optionally split the three large `Canvas` lambdas into named layer functions.

### Phase 3 — Desktop NWS fetch unification (§2.3)
Extract the NWS forecast+observation fetch into a shared fetcher returning `RawFetch`, with the
platform supplying the API client, station cache, and observation-source adapter. Android's
`NwsForecastMapper`/`NwsObservationSource` and desktop's `fetchNwsForecast`/`fetchObservationBundles`
both become adapters.

**Status (2026-09-09): deferred — not a safe quick win.** Evidence: the mapping *algorithms* are
already single-sourced (`NwsDailyMapper`, `NwsHourlyGridMerge`, `NwsObservationMapper`,
`SpatialInterpolator`, `TemperatureInterpolator`), and `NwsDailyMapper.buildDailyForecasts`
explicitly documents "Keeps desktop at parity with Android's NwsForecastMapper". What remains is not
a line-for-line fork but a difference in **output shape and mapping depth**: Android
`NwsForecastMapper.fetchFromNws` writes Room `ForecastEntity`/`HourlyForecastEntity` and runs the
richer accumulator pipeline (plausibility repair, hourly-divergence detection, phantom-day removal,
per-field source logging); desktop `fetchNwsForecast` returns the shared `RawFetch` via the simpler
`buildDailyForecasts` wrapper and folds observation fetching into the same function. Unifying means
changing one platform's forecast data path and output type, which is behavior-changing and needs its
own before/after data comparison. Recommend a dedicated plan if pursued.

### Phase 4 — Mechanical leftovers
A5 `TempUtils.display`, A7 `Dp` object, A6 residual age formatters, C4 doc/code decision. Then the
`WeatherDatabase` migration/auto-migration plan as its own document (not a drive-by).

**Status (2026-09-09): done.**
- **A7:** the eight identical `private fun Float.dp(density)` declarations in `:app` daily-view files
  were replaced by one `internal fun Float.dp` in `app/.../widget/Dp.kt`.
- **A5:** the remaining inline unit conversions were routed through `TempUtils.display`/
  `displayDelta` in `StatisticsWindow`, `ForecastHistoryWindow`, `ForecastHistoryViewLogic`,
  `ForecastDeltaLabel`, `TemperatureLabelResolver`, `CurrentTemperatureResolver` and
  `BlendTableFormatter`. What remains is `TempUtils`' own definition plus non-conversion thresholds
  (`if (useCelsius) 1.7 else 3.0`).
- **A6:** the two `formatAgeLabel` wrappers already delegate to `FetchDotLabel`; the remaining
  `StaleObservationFallback`/`BlendTableFormatter` styles are intentionally different and documented,
  so they stay.
- **C4:** `AGENTS.md` now states the shipped behaviour — Tomorrow.io is not default-visible (Android
  debug-only default, desktop never) but is user-selectable on both platforms.
- **Migrations:** scoped as [plans/260909-weatherdatabase-migration-strategy.md](260909-weatherdatabase-migration-strategy.md)
  (proposal only; no code changed).
- Verified: `./scripts/staggered-tests.sh` 4057 unit + 95 instrumented green.

---

## 7. Verification

1. Per phase: `./gradlew test` (all modules, `@Category` buckets enforced).
2. Phase 1: shared unit tests against a fake port, plus the existing Android Robolectric and desktop
   DB tests unchanged.
3. Phase 1 divergence fixes are behavior *changes*: assert the new filter/`lastWriter`/log payload
   in tests, and on the emulator query `app_logs` for `NWS_STATION_ACTUALS_OUTCOME` and
   `daily_history.lastWriter` before/after.
4. Phase 2: `./gradlew :desktop:compileKotlin`, focused desktop tests, then launch the desktop UI
   against the existing config and confirm popup/header/panel/daemon behaviour and logs.
5. `ktlintCheck`, `assembleDebug`, `:desktop:createDistributable` at the end of each phase.

## 8. Out of scope

- `WeatherDatabase` migration rewrite (separate plan + instrumented migration test).
- The daemon single-writer redesign (260813 H1/H2); Phase 2 only reshapes `runDaemon`.
- Rewriting the desktop Compose `Canvas` draw passes into shared drawing code — the geometry is
  already shared and Compose `DrawScope` vs Android `Canvas` cannot be unified without a new
  abstraction layer.
- `DesktopWeatherDao` (JDBC, not a fork).
