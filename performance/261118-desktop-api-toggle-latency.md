# Desktop: measure true click-to-render API toggle latency and eliminate toggle bottlenecks

**Date:** 2026-11-18
**Status:** implemented & verified (2026-11-18)
**Scope:** `:shared` (`SourceSwitchRunner`, `HeaderContent`, `TodayColumnOverlayContentResolver`, `ActualsAggregator`, `ActualTemperatureSeriesBuilder`), `:desktop` (`DesktopSourceSwitch`, `DesktopUiApplication`, `DesktopWidgetPopup`, `DaemonRuntime`, `DesktopWeatherRepository`, `TemperatureGraph`, `DesktopDailyForecastModel`), `:app` (`HeaderWidthChecker` wired to shared header precip window)

## Report

"Desktop: Performance when I click on an API is not great. Can you measure performance? Ideas on improving performance when toggeling API?"
"The benchmark is not correct. I see a second usually and more. Does the end to end benchmark for API toggle know when rendering has finished? Does it start on click?" (user, 2026-11-18)

## Evidence

### 1. What `SOURCE_SWITCH_E2E` currently measures (and misses)

- **Start:** `DesktopSourceSwitch.switchTo` records `startMs` inside the click handler (`cycleSourceForward` → `cycleDisplaySource` → `switchTo`), before saving `config.json`.
- **Stop:** `DesktopSourceSwitch.onDataShown(cached.sourceId)` is called in `DesktopUiApplication.kt` immediately after `forecast = cached` — **before** Compose recomposes, runs UI-thread series/overlay builders, resolves icon painters, lays out text, or draws the Skia frame.

Across 656 desktop switches in `~/.local/share/weather-widget/weather.db` (`app_logs`):
- `phase=data_ready`: **min 157 ms, p50 654 ms, mean 696 ms, p90 960 ms, max 5,075 ms** (uniform across all 7 sources: p50 636–683 ms).
- Latest 5 clicks (`13:32:22–13:32:29`, all cached `action=NONE`, `persistMs=1–10 ms`): **402, 524, 526, 627, 716 ms** to `data_ready` alone.

### 2. Four full `loadCached()` runs per click (4× amplification)

`app_logs` around a single click (`13:32:26`, `GOOGLE_WEATHER → TOMORROW_IO`, `sinceSwitchMs=716`):

```
t+000 ms  CONFIG_SAVE + SOURCE_SWITCH_E2E phase=done persistMs=10
t+183 ms  CURR_TEMP_RESULT #1   (daemon runLaunchRefresh loadCached)
t+281 ms  CURRENT_STATUS_PUBLISH (daemon) → notifyDataUpdated() [socket + .data-updated]
t+351 ms  LAUNCH_REFRESH_CHECK  reason=source_change action=NONE
t+549 ms  CURR_TEMP_RESULT #2   (UI LaunchedEffect(repository) loadCached)
t+551 ms  CURR_TEMP_RESULT #3   (UI UiNotifyClient socket:push loadCached)
t+601 ms  CURR_TEMP_RESULT #4   (UI DataUpdateWatcher .data-updated loadCached)
t+706 ms  SOURCE_SWITCH_E2E phase=data_ready sinceSwitchMs=716
```

1. UI `LaunchedEffect(repository)` runs `loadCached()` #1.
2. Daemon `.config-changed` → `runLaunchRefresh(reason = "source_change")` runs `loadCached()` #2 and unconditionally calls `notifyDataUpdated()` (`DaemonRuntime.kt:889`) even when `action=NONE` (no network fetch, DB unchanged).
3. UI `UiNotifyClient` receives `"socket:push"` → `reloadCachedForecast` runs `loadCached()` #3.
4. UI `DataUpdateWatcher` sees `.data-updated` from the same `notifyDataUpdated()` → `reloadCachedForecast` runs `loadCached()` #4.

In sessions where UI load #1 finished *before* the daemon's `notifyDataUpdated()` arrived (`12:36`), `sinceSwitchMs` dropped to **217–298 ms**, matching a single `loadCached()`, while the 2 redundant reloads still ran right afterwards and forced 2 extra recompositions.

### 3. Single `loadCached()` cost: 100–303 ms (warm JVM, zero contention)

Read-only benchmark against a snapshot of live `~/.local/share/weather-widget/weather.db` (24 MB; 13,728 `observations`, 19,484 `hourly_forecasts`):

| Source | Total | `getCloudActuals` | `getObservationsInRange` (13,728 rows) | `getHourlySettledAllSources` | `DailyActualsAssembler` | `getHourlyWithHistory` | Other |
|---|---:|---:|---:|---:|---:|---:|---:|
| **NWS** | **303 ms** | **113 ms** | **64 ms** | **53 ms** | 31 ms | 11 ms | 31 ms |
| **Silurian** | **215 ms** | **61 ms** | **58 ms** | **56 ms** | 2 ms | 11 ms | 27 ms |
| **Open-Meteo** | **178 ms** | **40 ms** | **35 ms** | **55 ms** | 8 ms | 11 ms | 29 ms |
| **Tomorrow.io** | **162 ms** | **41 ms** | **42 ms** | **35 ms** | 13 ms | 7 ms | 24 ms |
| **WeatherAPI** | **160 ms** | **39 ms** | **38 ms** | **48 ms** | 4 ms | 6 ms | 25 ms |
| **Google** | **152 ms** | **40 ms** | **38 ms** | **38 ms** | 2 ms | 8 ms | 26 ms |
| **OWM** | **100 ms** | 0 ms | **36 ms** | **36 ms** | 1 ms | 6 ms | 21 ms |

*(15× `db.getConnection()` open+close overhead measured 3 ms total — not a bottleneck.)*

- **`observations` (30 days = `MAX_BACK_HOURS = 720`, 13,728 rows) is read twice per `loadCached()`**: once inside `DesktopWeatherDao.getCloudActuals` (`rCloud`: 39–113 ms, plus 30-day METAR cloud blending on NWS) and again ten lines later at `DesktopWeatherRepository.kt:240` (`obs`: 35–64 ms).
- **`getHourlySettledAllSources` reads and stitches all 7 sources × 40 days** (`now - 720h` to `now + 240h`, 35–56 ms) on every click, solely so `HeaderContent.precipLabels` can reserve width for the widest source's near-term rain % label.

### 4. Unmeasured UI-thread compute *after* `forecast = cached`: 100–512 ms

Benchmarking the synchronous UI-thread calls executed during Compose composition on the same DB snapshot:

| Source | `ActualTemperatureSeriesBuilder.build` (Hourly, 144h ctx) | `TodayColumnOverlayContentResolver.resolveLatest` (Daily) |
|---|---:|---:|
| **NWS** | **63 ms** (blend-cache hit) – **512 ms** (miss) | **242 ms** |
| **Tomorrow.io** | **110–114 ms** | **101 ms** |
| **Open-Meteo** | 17–21 ms | 15 ms |
| **Silurian / WeatherAPI / Google / OWM** | 1–6 ms | 0–3 ms |

- `TemperatureGraph.kt:231` calls `ActualTemperatureSeriesBuilder.build(...)` **synchronously on the UI thread without `remember`** (re-running on every recomposition, including the 2 redundant daemon-triggered reloads), with a 144h (6-day) context floor even at default 9h zoom.
- `DesktopDailyForecastModel.build` calls `TodayColumnOverlayContentResolver.resolveLatest(...)` synchronously on the UI thread over all 13,728 `rawObservations`.
- Plus Compose layout, `painterResource`, `textMeasurer.measure`, Skia Canvas draw, and frame presentation — bringing total click-to-pixels latency to **1.0–1.5+ seconds**.

## Root causes

1. **Incomplete measurement:** `SOURCE_SWITCH_E2E` stops at `forecast = cached` (`phase=data_ready`), missing UI-thread blend/overlay compute and Compose/Skia draw.
2. **4× `loadCached()` storm on every click:** daemon `source_change` calls `notifyDataUpdated()` when `action=NONE`; UI listens to both socket push and `.data-updated` without deduplication or in-flight reload cancellation.
3. **Redundant & oversized SQLite reads in `loadCached()`:** reads 30 days of all-API `observations` twice (2 × 13,728 rows) and stitches 40 days of all-source `hourly_forecasts` just for the header rain-% reserve.
4. **No in-memory per-source snapshot cache in the UI process:** cycling back to a source viewed seconds ago repeats the entire DB load.
5. **Heavy blend/overlay work runs uncached on the Compose UI thread:** `ActualTemperatureSeriesBuilder.build` (not `remember`ed) and `TodayColumnOverlayContentResolver.resolveLatest` run synchronously during composition over all 13,728 observations.

## Proposed plan

### Phase 1 — Honest end-to-end measurement (`phase=rendered`)
1. Add `SourceSwitchRunner.renderedLine(...)` (`phase=rendered sinceSwitchMs= renderMs=`) to `:shared` (`SourceSwitchRunner.kt`), where `sinceSwitchMs` is click → post-draw frame and `renderMs` is `data_ready` → post-draw frame.
2. Keep `Pending` alive in `DesktopSourceSwitch` after `onDataShown(sourceId)` (recording `dataReadyMs`) until `onFrameRendered(sourceId)` fires once after the first Compose frame that actually draws `snapshot.sourceId == pending.to`.
3. Wire `onFrameRendered` in `DesktopWidgetPopup.kt` using `Modifier.drawWithContent` + `LaunchedEffect` / `withFrameNanos` (or post-draw callback on the popup's root content when `snapshotIsSelectedSource`) so it fires only after the new source's graph + header have drawn.
4. Capture a real "before" baseline on the running desktop app across all 7 sources in both Hourly and Daily views.

### Phase 2 — Stop the 4× `loadCached()` storm
1. **Daemon (`DaemonRuntime.runLaunchRefresh`):** when `reason == SOURCE_CYCLE_REASON` (or `SOURCE_LIST_EDIT_REASON`), publish to the panel from `cached`, but **do not call `notifyDataUpdated()`** on the cache-only path — only call `notifyDataUpdated()` when an actual refresh writes new data (or fails with a status change). The UI already loaded its own cache on config change.
2. **UI (`DesktopUiApplication.kt`):**
   - Cancel/replace any in-flight `reloadCachedForecast` job (track a single `reloadJob: Job?`) so concurrent triggers never pile up.
   - Deduplicate `UiNotifyClient` (`socket:push`) and `DataUpdateWatcher` (`.data-updated`): suppress the file-watch reload when a socket push already arrived within a short debounce window (e.g. 500 ms), keeping `.data-updated` purely as a fallback when the socket is disconnected.

### Phase 3 — Trim `loadCached()` & share window rules (`:shared` + `:desktop` + `:app`)
1. **Eliminate the duplicate 13,728-row observation read (`:desktop`):** load `observations` once in `DesktopWeatherRepository.loadCached()` and pass them into `MetarCloudBlender.fromSiteRows(...)` directly instead of letting `getCloudActuals` re-query `getObservationsInRange`.
2. **Share the header rain-% reserve window (`:shared` `HeaderContent` + `:app` `HeaderWidthChecker` + `:desktop` `DesktopWeatherRepository`):**
   - Move the `fromMs..toMs` window rule for `allSourcesHourly` into `HeaderContent` in `:shared` (matching Android's existing `minOf(now, centerTime) - 1h .. maxOf(now, centerTime) + LINE_RAIN_WINDOW_HOURS` instead of desktop's 40-day `-720h .. +240h` scan).
   - Wire both Android's `HeaderWidthChecker` and desktop's `DesktopWeatherRepository.loadCached()` to that single shared window rule.

### Phase 4 — Shared blend/overlay speedups (`:shared`) + Desktop UI caching (`:desktop`)
1. **Pre-filter observations to the resolution window inside `:shared` (`ActualsAggregator.resolveCurrentObservationDetails` / `TodayColumnOverlayContentResolver`):**
   - Today Android manually filters observations in `DailyGraphRenderer` before calling `resolveAt`, while desktop passes all 13,728 rows into `resolveLatest` (242 ms NWS / 101 ms Tomorrow.io). Filtering inside `:shared` protects both platforms and removes caller duplication.
2. **Profile & optimize `ActualTemperatureSeriesBuilder.build` / `ActualsAggregator` in `:shared`:**
   - Used by both Android (`TemperatureHourDataBuilder`) and desktop (`TemperatureGraph`). A blend-cache miss currently takes 512 ms on NWS and 110 ms on Tomorrow.io. Profile the hot loop (e.g., per-point filtering/allocation across all 13,728 rows vs window-sliced input) and optimize in `:shared` so both Android widget paints and desktop toggles benefit.
3. **UI-process per-source `ForecastSnapshot` cache (`:desktop` `DesktopUiApplication.kt`):**
   - Keep a small `Map<SnapshotCacheKey, ForecastSnapshot>` keyed by `(sourceId, lat, lon, personalStationWeight, actualsProviderOverride)` (bounded to `effectiveSources.size`, ~7 entries).
   - On `LaunchedEffect(repository)` (source toggle): if a cached snapshot exists for the new `repository`'s key, apply `forecast = cached` **immediately** (0 ms DB read) and log `data_ready`.
   - Invalidate the cache on: daemon data update (`socket:push` / `.data-updated`), location change, settings change affecting rendering/weighting, or staleness (re-resolving `currentTemp` in memory via `repo.resolveCurrentTempInMemory` when needed).
4. **Eliminate redundant UI-thread recomputation (`:desktop`):**
   - `remember` `ActualTemperatureSeriesBuilder.build(...)` in `TemperatureGraph.kt` keyed on `(hourly, observations, center, displaySourceId, backHours, forwardHours, personalStationWeight, hourlyActuals)` (bucketing `now` so unrelated recompositions don't churn).
   - `remember` `DesktopDailyForecastModel.build(...)` (or its `todayOverlay` resolution) in `DailyForecastGraph.kt` / `DesktopWidgetPopup.kt` so hover/state updates don't re-run `TodayColumnOverlayContentResolver.resolveLatest` on the EDT.

## Expected impact

| Scenario | Before (measured) | Expected after |
|---|---:|---:|
| Click → `data_ready` (cold source) | 654 ms p50 (960 ms p90) | ~40–60 ms |
| Click → `data_ready` (warm/cached source) | 654 ms p50 | **< 5 ms** |
| Click → `rendered` (true click-to-pixels) | ~1,000–1,500+ ms | **~30–120 ms** |

## Tests & living documentation

- **Unit + integration tests:**
  - `DesktopSourceSwitchTest`: verify `phase=data_ready` and `phase=rendered` (`renderMs`, `sinceSwitchMs`) emit in order and clear pending state.
  - `:shared` tests: `HeaderContent` precip-reserve window bounds, and `TodayColumnOverlayContentResolver` / `ActualTemperatureSeriesBuilder` output equivalence with wide vs pre-filtered observation lists.
  - `DaemonRuntime` / `LaunchRefresh` integration test (real SQLite): verify `source_change` with `action=NONE` publishes panel state without firing `notifyDataUpdated()`.
  - `DesktopWeatherRepository` integration test (real SQLite): verify single-read `loadCached()` produces identical `retroCloudActual`, `retroCloudBands`, `dominantCloudContribution`, and `allSourcesHourly` header precip labels as before.
  - UI snapshot cache invalidation test: verify cache hit on source toggle, and invalidation on daemon update / location change / `personalStationWeight` change.
- **Living docs:** update [arch/operations.md](file:///home/dcar/projects/weather-widget/arch/operations.md) ("Slow source switches and desktop SQLite lock stalls") to document `phase=rendered`, the single-reload path, and the updated normal latency range.
- **End-of-task verification:** `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`, then click through all 7 sources in both Hourly and Daily views and report the measured before/after `phase=data_ready` and `phase=rendered` numbers from `app_logs`.

## Follow-up from live `phase=rendered` logs (2026-11-18 14:41)

Live `phase=rendered` immediately exposed the remaining ~500–800 ms bottleneck:
- `SILURIAN`: `data_ready=496 ms`, `renderMs=538 ms`, **`rendered=1,034 ms`**
- `OPEN_METEO`: `data_ready=488 ms`, `renderMs=532 ms`, **`rendered=1,020 ms`**
- `GOOGLE_WEATHER`: `data_ready=440 ms`, `renderMs=559 ms`, **`rendered=999 ms`**
- `TOMORROW_IO`: `data_ready=647 ms`, `renderMs=467 ms`, **`rendered=1,114 ms`**

Inspecting `~/.local/state/weather-widget/autostart-20261118-143832.log` (which had grown to **8.7 MB in 3 minutes**) revealed why:
1. **`NightRainLabelPlacer.place()` (`:shared`)** evaluated ~10,000 grid spots × ~280 1px `rowBands` obstacles × ~35 label `inkBands` per night rain label, allocating a `Box` object inside the innermost loop and an `ArrayList<Spot>` of 10,000 objects per label — costing **250–559 ms per `DailyForecastGraph` frame** (and blocking Frame #1 for ~230 ms before `LaunchedEffect(repository)` could even start `loadCached()`).
2. **`DailyRainLabelLayout.kt` (`:shared`)** formatted all ~2,000 obstacle rects into a **90 KB `Log.v` string on every frame**.
3. **`DesktopWidgetPopup.kt`** keyed `remember(config, ...)` on all of `config` (including `weatherSource`), rebuilding `DesktopDailyForecastModel` on Frame #1 before the new snapshot arrived; and `TextLayoutResult.inkBands` re-scanned pixel maps on every draw.

### Phase 5 — Optimize `NightRainLabelPlacer` (`:shared`) + eliminate Frame #1 waste
- Rewrote `NightRainLabelPlacer.place()` in `:shared` to pre-filter obstacles to the search bounding box + `comfortPx`, narrow horizontally per column, flatten to primitive `FloatArray`s with zero `Box`/`Spot` allocations in the grid loop, and evaluate exact `score()` only for the winning `best` and `runnerUp`.
- Replaced the 90 KB `obstacles=[...]` string in `DailyRainLabelLayout.kt` with `obstacles=${obstacles.size}`.
- Cached `TextLayoutResult.inkBands` in `DailyForecastGraph.kt`, and narrowed `DesktopWidgetPopup.kt`'s `dailyState` `remember` keys to exclude `config.settings.weatherSource`.
