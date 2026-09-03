# Weather Widget Architecture

> Last updated 2026-08-12. This document supersedes the May-2026 version and reflects the
> current three-module codebase (`:app`, `:shared`, `:desktop`).

## 1. Overview

Weather Widget is a resizable Android home-screen widget plus a Linux desktop tray/window app
that render the same weather content. The system has grown well beyond "a widget with two APIs"
into a multi-provider forecasting/observation pipeline with forecast-accuracy tracking,
forecast-history snapshots, climate-normals gap filling, and cross-platform graph rendering.

### Scale (measured 2026-08-12)

| Module | Main lines | Test lines | Test files | Role |
|--------|-----------|-----------|-----------|------|
| `:app` (Android) | ~46k | ~54k | 272 unit + 41 instrumented | Widget, Room DB, repositories, scheduling, UI activities |
| `:shared` (pure JVM) | ~18k | ~17k | 112 unit | API clients, graph math/labels, actuals blending, cross-platform logic |
| `:desktop` (Compose) | ~14k | ~6.5k | 37 unit | Tray/window app, two-process daemon/UI split |

The `:shared` test suite runs entirely on the JVM in under a second (~513 tests) — it is the
project's algorithmic core, and everything that can be made platform-agnostic lives there.

### Weather sources

`WeatherSource` (in `shared/.../data/model/WeatherSource.kt`) defines the providers:

- **NWS** — US-only, keyless, `STATION_OBSERVATION` history (ASOS/AWOS via `/stations/{id}/observations`).
- **Open-Meteo** — global, keyless, `REANALYSIS_ARCHIVE` history (ERA5).
- **Silurian**, **WeatherAPI**, **Visual Crossing**, **OpenWeatherMap**, **Tomorrow.io** — keyed providers with varying historical products.
- **GENERIC_GAP** — a synthetic "Climate Avg" source used to fill missing days from climate normals (not a real API).

Each source now declares a `HistoricalDataKind` describing *what* its past-hour product is
(station observation vs. reanalysis vs. provider archive), which gates how it may be used as
"actual" data.

## 2. Module boundaries

The single most important architectural decision is the `:shared` seam:

- **`:shared`** owns everything that is pure JVM: API clients (`NwsApi`, `OpenMeteoApi`, …),
  data *models*, the graph geometry + label-placement engine, actuals aggregation, observation
  merging, accuracy math, and shared utilities. It must not reference `android.content.Context`,
  Room, RemoteViews, or widget preferences.
- **`:app`** owns Android coupling: Room entities/DAOs, Hilt graph, widget lifecycle, scheduling,
  RemoteViews rendering glue, and the activities.
- **`:desktop`** reuses `:shared` models and API clients, and reimplements the *rendering* and
  *orchestration* layers in Compose for Desktop (its own thin config/location/repository classes).

This seam is what makes the 112-file pure-JVM test suite and the Android↔desktop parity work.
The renderer "engines" (label placement, axis scaling, curve smoothing, overlay planning) are all
in `shared/.../shared/graph/` precisely so both platforms compute identical geometry and the
platform layer only *draws* the result.

## 3. Data layer

### 3.1 Android persistence (Room)

`app/.../data/local/WeatherDatabase.kt` is a single Room database at **schema version 61** with
8 entities and 8 DAOs:

| Entity | Purpose |
|--------|---------|
| `ForecastEntity` | Daily forecast rows, one per `(targetDate, dateOfPrediction, source, lat, lon, batchFetchedAt)` — keeps per-batch history for evolution/accuracy. |
| `HourlyForecastEntity` | Hourly forecast points for interpolation + hourly view. |
| `HourlyForecastHistoryEntity` | Hourly forecast *snapshots* bucketed by `timestampToGroupPredictions` — "what we predicted at time T". |
| `ObservationEntity` | Station observations keyed `(stationId, timestamp, lat, lon)`; carries `api`, `qcFailed`, `isWebFallback`, precip. |
| `DailyHistoryEntity` | Per-day rolled-up actuals (`computedHighTemp/LowTemp`, `apiHighTemp/LowTemp`, frozen forecast columns, `actualsSource`, `lastWriter`). |
| `ClimateNormalEntity` | Monthly-day climate normals for gap-filling. |
| `AppLogEntity` | Persistent diagnostic log (`app_logs`). |
| `ApiUsageEntity` | Per-day per-source call counts for quota visibility. |

The schema carries ~17 hand-written migrations (44→61), several of which perform delicate data
surgery rather than simple column adds:

- **Float-coordinate fragmentation** is a recurring theme: float lat/lon inside primary keys let
  GPS/geocoding jitter strand rows. Migrations 47/48 and 49/50 round lat/lon onto a quantization
  grid and dedupe fragments; `LocationMatch.quantize` is the shared contract that all writers must
  respect.
- **Table renames with index rebuilds** (50/51 `daily_extremes`→`daily_history`), **sentinel-data
  poisoning cleanup** (58/59 wipes NWS "API actuals" that were really forecast/ERA5 data), and
  **conditional/idempotent ALTERs** (`addColumnIfMissing`) for self-healing.
- A `healCorruptDatabaseVersion` path corrects version-vs-schema drift left by older destructive
  migrations before Room runs.

Location identity is the subtlest correctness issue in the whole data layer: `LocationMatch`
(shared) defines how a coordinate quantizes to a site, and a mis-keyed row is a permanent
fragment that `selectNearestSite` silently drops.

### 3.2 Desktop persistence

Desktop has its own thin SQLite layer (`shared/.../data/local/desktop/`): `DesktopWeatherDatabase`
+ `DesktopWeatherDao` (1,127 lines, 37 queries) over `DesktopForecastRow`, `DesktopObservationEntity`,
`DesktopLogEntity`, `DesktopStationCacheEntity`, etc. This is a *second* persistence implementation
parallel to the Android Room layer — not a shared one. It exists because Room is Android-only, but
it means DAO logic is (partially) duplicated across platforms.

### 3.3 Repositories (Android)

The repository layer is the coordination tier between APIs and DAOs:

- **`WeatherRepository`** — public facade used by the worker/UI; `getWeatherData`, `refreshCurrentTemperature`, backfill entry points.
- **`ForecastRepository`** + **`ForecastFetchCoordinator`** — selects which sources to fetch (per-source staleness via `ForecastFetchPolicy`/`ForecastStalenessPolicy`), fans out Ktor calls concurrently, classifies/persists results, and logs per-source failures.
- **`ObservationRepository`** / **`CurrentTempRepository`** / **`CurrentObservationReader`** — station observations and the computed NWS current-temperature blend.
- **`DailyActualsStore`** (673 lines) / **`DailyHistorySnapshotter`** — the "actual high/low" roll-up.
- **Backfillers** — `NwsObservationBackfiller`, `NwsApiDailyActualsFetcher`, `WeatherApiHistoryBackfiller`, `ClimateGapFiller` — idempotent, run "if needed".
- **`WeatherRetentionManager`** — 30-day rolling retention.

## 4. The sync pipeline

`WeatherWidgetWorker` is now a **thin dispatcher**. It decodes `WorkInput` and routes to one of
three modes, delegating the full sync to `FullSyncPipeline`:

```
GPS resample (piggyback) → location resolution/promotion → fetch → snapshots
→ NWS observation backfill → actuals recompute → repaint all widgets → re-schedule
```

`FullSyncPipeline.run` documents its own timing (`SYNC_STAGE`, `SYNC_PERF` rows when >500 ms) and
its races. Two notable race-handling patterns:

1. **Hourly source snapshot re-read**: hourly forecasts are loaded *before* the fetch but scoped to
   the sources visible at load time. If the user toggles a source mid-fetch, the loaded rows would
   contain zero rows for the newly-displayed source; the pipeline detects this
   (`HourlyForecastLoader.sourcesMissingFromLoad`) and re-reads once before actuals recompute and
   repaint.
2. **Per-source staleness, not a global timestamp**: no "just fetched, skip everything" gate at the
   worker level; freshness is enforced per source one layer down, so a genuinely stale source is
   never deferred for a whole cooldown window.

### Work modes (`WorkInput`)

- **full sync** — everything above.
- **`currentTempOnly`** — current-temperature refresh, gated by `CurrentTempFetchPolicy` (battery/interactive).
- **`nonPrimaryCurrentTempOnly`** — refresh the *visible but not displayed* sources in the background.
- **`observationBackfillOnly`** — NWS observation backfill with an explicit coordinate (refuses to run without a real location, to avoid filing mis-keyed rows).
- **`uiOnlyRefresh`** — re-render from cache only, no network.

The `WorkerExceptionHandler` + `ProcessExitLogger` pair exists because a cancelled in-flight worker
can **segfault the ART interpreter** on debuggable builds (native crash, invisible to the JVM crash
logger). Enqueue policies are therefore disciplined: `KEEP`/`APPEND_OR_REPLACE` for unique work,
`REPLACE` only for delayed/not-yet-running work, `UPDATE` for periodic work.

## 5. Widget layer

### 5.1 Entry and interaction routing

```
WeatherWidgetProvider (AppWidgetProvider, Hilt)
  └─ WidgetStartupCoordinator            (onUpdate path)
  └─ WidgetIntentRouter                  (resize; public interaction facade)
       └─ WidgetInteractionCoordinator   (per-widget mutex + app-log metadata)
            └─ WidgetIntentActionHandler (navigate / toggle API / toggle view / set view / cycle zoom)
                 └─ view handlers        (DailyViewHandler, TemperatureViewHandler,
                                          PrecipViewHandler, CloudCoverViewHandler)
```

Key properties:

- **Per-widget interaction serialization**: `WidgetInteractionCoordinator` takes a per-widget lock
  so overlapping taps on the same widget cannot interleave; every interaction is logged with
  before/after state metadata (`NAV`, `TOGGLE_API`, `CYCLE_ZOOM`, …).
- **Instant feedback from cache**: navigation and view toggles are direct DB reads (via
  `goAsync()`-backed `BroadcastAsyncRunner`), then a conditional background fetch if stale.
- **`WidgetRenderer` → `WidgetPaintCoordinator`** is the paint path: it turns the loaded
  `weatherList`/`hourlyForecasts`/`dailyActuals`/`currentTemps` into RemoteViews per widget, with
  an escape hatch (`reloadActuals`) for source-toggled widgets.

### 5.2 View modes & state

`WidgetStateManager` persists per-widget state (view mode, day offset, hourly offset, zoom window,
display source, accuracy mode). The view modes are Daily (forecast bars), Hourly (temperature /
precipitation / cloud-cover graphs), and text mode for one-row widgets. Zoom is a multi-stage
window (`ZoomStage`/`ZoomWindow` in shared) with configurable narrow span.

The **touch-zone problem** is intrinsic here: RemoteViews cannot express real touch layout, so tap
routing is computed from rendered geometry (column x-ranges, header rows, footer, day-click zones)
and mapped back to actions via `*TouchTargets`/`*ClickHelper` classes. This is a large fraction of
the bug history (see the `plans/` entries named "touch zone / click routing / tap").

### 5.3 Renderers

Android renderers (`DailyForecastGraphRenderer`, `PrecipitationGraphRenderer` (951 lines),
`TemperatureGraphRenderer` split into `TemperatureGraphSeriesRenderer`/`TemperatureGraphAnnotationRenderer`/…,
`CloudCoverGraphRenderer`, `ForecastEvolutionRenderer`) all draw into `Canvas` bitmaps. The
*decision-making* (where labels go, how curves are smoothed, how the today-column overlay is
planned) lives in `:shared`, and the Android file only executes the plan.

## 6. Graph label placement — the algorithmic core

The hardest and most-iterated part of the codebase is the graph **label placement engine** in
`shared/.../shared/graph/`:

| File | Lines | Responsibility |
|------|------:|----------------|
| `TemperatureLabelEngine` | 1,202 | Per-role label candidate generation, curve avoidance, leader-line displacement |
| `TemperatureLabelResolver` | 1,031 | Collision resolution / ordering across candidate labels |
| `GraphLabelPlacementUtils` | 363 | Overlap tests, minor-overlap budgets, shared geometry |
| `TemperatureExtrema` | 399 | Which points on the curve are "the high/low/actual high/…" |
| `ValueLabelEngine` | 390 | Generic numeric label placement |
| `DualHighLabel` / `GhostLineLabel` / `FetchDotLabel` / `ForecastDeltaLabel` / `DominantStationLabel` | ~215 each | Special label roles with their own rules |
| `TodayColumnOverlayPlanner` / `TodayColumnOverlayBlocks` / `LargeTodayOverlayPolicy` | ~513 | The "today" column station-overlay layout |

This is a continuous collision-avoidance layout problem: each label has a **role**
(`TemperatureRole`: HIGH, LOW, ACTUAL_HIGH, ACTUAL_LOW, START, END, LOCAL, …), each role has its own
curve-avoidance margin and overlap tolerance, labels emit **leader lines** when displaced from their
anchor, and the engine must produce pixel-identical geometry on Android Canvas and Desktop Compose
(shared pure functions + a platform `LabelTextMetrics` for text measurement). The `plans/` directory
contains dozens of "label overlap/collision/leader-line/ghost-line" investigations accumulated over
months — this subsystem is where most rendering bugs live.

## 7. Actuals / observation blending

The second-hardest subsystem is computing "the actual high/low for the day" from heterogeneous
observation sources. Shared pieces:

- `ActualTemperatureSeriesBuilder` (875 lines) + `ActualsAggregator` + `ApiActualPicker` +
  `DailyActualsSource` + `DailyForecastSelector` + `HistoricalActualsBackfill` +
  `NwsDailyExtremesFetch` + `StationDailyExtremes`.
- `LatestObservationMerge`, `NwsQualityControl` (QC-flag rejection), `ObservationFallbackPolicy`,
  `ObservationSourceMatcher` (shared `observations/`).
- `YesterdayDeltaCalculator`, `TodayColumnOverlayContentResolver`.

The complexity is reconciling, per day, the NWS station readings (with QC flags and Synoptic web
fallback), Open-Meteo's ERA5 archive, and the provider archive products, into one consistent
`computedHigh/Low` that matches across the daily view, hourly graph, and accuracy stats — while
handling sentinel temperatures, personal-station discounting, location fragmentation, and
cross-location leaks. The `DailyHistoryEntity` stores both the IDW-blended value
(`computedHighTemp`) and the provider-reported value (`apiHighTemp`), plus an `actualsSource` /
`lastWriter` provenance trail so the pipeline is auditable.

## 8. Scheduling & battery strategy

The Android update system remains a battery-first multi-tier design, now with more tiers:

| Update type | Frequency | Mechanism | Wakeup |
|-------------|-----------|-----------|--------|
| Current-temp UI | temp-rate adaptive | `CurrentTempUpdateScheduler` + WorkManager | opportunistic |
| Opportunistic UI | ~30 min | `OpportunisticUpdateJobService` (JobScheduler) | piggyback |
| Full data fetch | 60–480 min battery-aware | `WeatherWidgetWorker` (periodic) | controlled |
| Non-primary source refresh | gated | `NonPrimaryObservationScheduler` | opportunistic |
| User interaction | immediate | direct DB read | — |
| Screen unlock | immediate | `ScreenOnReceiver` | — |

Policy logic is extracted into testable classes: `BatteryFetchStrategy`, `BatteryStatePolicy`,
`CurrentTempFetchPolicy`, `ForecastFetchPolicy`, `ForecastStalenessPolicy`, `StartupFetchPolicy`,
`PowerConnectedRefreshPolicy`, `UIUpdateIntervalStrategy`, `WidgetRefreshPolicy`. `WidgetLoopScheduler`
manages the current-temp / non-primary re-arm loops after each run.

## 9. Desktop architecture — two-process split

The desktop app is a **headless daemon + ephemeral UI** split (see `DesktopProcess.kt`,
`DaemonProcess.kt`, `Main.kt`):

```
launcher (scripts/desktop-app-launcher-and-autostart.sh)
  └─ jpackage binary "daemon" mode
       ├─ owns: weather DB, fetch loops, config, genmon panel socket, tray
       └─ launches: "ui" mode child process (Compose window/tray) on demand
```

Inter-process coordination uses **trigger files** in the shared XDG data dir (`.data-updated`,
`.refresh-requested`, `.quit-<launchId>`, `.ui-show`) plus a socket push (`PanelIpcServer` /
`UiNotifyChannel`) for reliable non-lossy notification, with a slow polling fallback
(`UI_FALLBACK_TICK_MS`) in case a watch event is missed. Single-instance is enforced by the
`.quit-<launchId>` token scheme (`signalIncumbentToQuit` / `supersededByNewerInstance`).

The daemon does substantial Linux integration:

- `gdbus` monitoring of `logind` `PrepareForSleep` (suspend/resume) and NetworkManager
  `StateChanged`/`Connectivity` (network restore), with debounce and jitter to avoid the
  post-wake "thundering herd" of re-fetches.
- Resume detection by wall-clock-jump heuristics on a 30s heartbeat (`isSuspendJump`).
- A "network warm-up grace window" (`isNetworkWarmupWindow`) so post-wake DNS failures are not
  surfaced as hard errors.
- `java.awt.headless=true` in the daemon; negative-DNS-cache TTL = 0.

`Main.kt` is a thin process entry point (<60 lines) that dispatches to `runDaemon` or the
extracted desktop UI composition root in `DesktopUiApplication.kt` (with dedicated collaborators:
`DesktopWidgetPopup.kt`, `DesktopWidgetHeader.kt`, `DesktopWindowHosts.kt`, and
`DesktopDayClickNavigation.kt`). Desktop rendering (`DailyForecastGraph` 1,082 lines,
`TemperatureGraph` 967, `DesktopGraphUtils` 753) mirrors the Android renderers but reuses the shared
graph engines for geometry.

## 10. Observability

The project has an unusually strong diagnostics culture, centered on the persistent `app_logs`
table and the shared `Log` router:

- **Tiered persistence**: `VERBOSE` is the explicit "do not persist" tier (high-frequency
  render/poll traces, logcat/console only); `DEBUG` and above persist to `app_logs`. The boundary is
  wired at the `CurrentTemperatureResolver.dbLogger` assignment in both `AppModule` and `DaemonProcess`.
- **Sparse, queryable events**: `SYNC_START/SUCCESS/FAILURE`, `SYNC_PERF`, `CURR_FETCH_*`,
  `WIDGET_LIFECYCLE`, `NAV`/`TOGGLE_API` interaction rows, `OBS_*_BACKFILL_*`, `LOCATION_MIGRATION`.
- **`ProcessExitLogger`** logs `ApplicationExitInfo` (the only in-app source for native/LMK/ANR
  deaths), critical because the worker-cancel crash is a *native* crash invisible to the JVM logger.
- **`WidgetPerfLogger` / `InteractionTimingLogger` / `WidgetUpdateTracker`** track per-interaction
  and per-paint latency.
- API calls are counted per-source per-day in `ApiUsageEntity`.

## 11. Testing strategy

The strategy is **pure-function extraction first, mocking last** (see `arch/testing-strategy.md`):

1. Pure JVM logic → plain unit tests (the entire `:shared` suite).
2. Needs Context/Room/prefs → Robolectric via `com.weatherwidget.test.RobolectricTest`.
3. Needs real Canvas/Bitmap, RemoteViews `performClick`, real view measure/layout, or real SQLite
   migrations → instrumented `androidTest` (emulator-first via `scripts/emulator-tests.sh`).

Every test class carries a `@Category` duration bucket (Short/Medium/Long) in all three modules,
enforced by build validation. The test suite is enormous relative to the main code (~1.3:1 in
`:app`), reflecting how much correctness-critical logic has been extracted.

## 12. Architectural assessment

### Strengths

- **Excellent pure-logic seam (`:shared`)** — the algorithmic core is platform-free and fast-tested; this is the foundation of Android↔desktop parity.
- **Testable policy extraction** — scheduling/fetch/staleness policies are small pure classes with unit tests, keeping the worker thin.
- **Deep observability** — the tiered app-log system and process-exit logging turn "dead widget" incidents into queryable evidence.
- **Disciplined concurrency** — per-widget interaction locks and WorkManager enqueue-policy rules encode hard-won lessons (the native-crash trap) directly into the code comments.
- **Self-healing, idempotent data layer** — conditional migrations, fragment-dedupe, and retry-until-flag-consumed backfills.

### Tensions / risks

- **Concentration of complexity** in graph label placement, actuals blending, and widget
  touch-routing — the three areas that dominate the bug history.
- **God files persist** — `PrecipitationGraphRenderer` (951), `DailyViewHandler` (858),
  `DesktopWeatherDao` (1,127), `TemperatureLabelEngine` (1,202). Decomposition has been attempted
  repeatedly (there are plans for it) but large files keep re-accumulating (`Main.kt` was successfully
  decomposed into modular desktop UI components in Sept 2026).
- **Two parallel persistence layers** (Android Room vs. desktop SQLite) and some duplicated utility
  code across `app/util` and `shared/util` (e.g. `RainAnalyzer`, `TempUtils`, `NavigationUtils`,
  `SunPositionUtils` exist in both). A "shared code deduplication" effort is ongoing.
- **Documentation lag** — ~450 files in `plans/` but `ARCHITECTURE.md` had not been refreshed since
  May; the plan archive is the de-facto knowledge base and is hard to navigate.

## 13. Complexity hotspots (ranked)

1. **Graph label placement engine** (`shared/graph`) — continuous collision-avoidance layout with per-role rules, leader lines, and dual-platform pixel parity. Most-recurring bug source.
2. **Actuals / observation blending** (`shared/actuals`, `shared/observations`, `ObservationRepository`, `DailyActualsStore`) — reconciling heterogeneous observation sources into one consistent daily "actual".
3. **Widget interaction & touch routing** (`handlers/`, renderers) — RemoteViews constraints force geometry-derived tap zones with per-widget state and serialization.
4. **Data layer + migrations** (`WeatherDatabase`, DAOs, `LocationMatch`) — float-coordinate fragmentation and 17 data-surgery migrations.
5. **Desktop daemon/UI process split** (`DaemonProcess`, `Main`, `DesktopProcess`, `PanelIpcServer`) — suspend/resume/network detection, single-instance, and IPC.
